/**
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.hadoop.hive.ql.exec.spark;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.google.common.base.Preconditions;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.exec.Operator;
import org.apache.hadoop.hive.ql.plan.OperatorDesc;
import org.apache.hadoop.mapred.Partitioner;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.Context;
import org.apache.hadoop.hive.ql.ErrorMsg;
import org.apache.hadoop.hive.ql.exec.JoinOperator;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.exec.mr.ExecMapper;
import org.apache.hadoop.hive.ql.exec.mr.ExecReducer;
import org.apache.hadoop.hive.ql.io.BucketizedHiveInputFormat;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.plan.BaseWork;
import org.apache.hadoop.hive.ql.plan.MapWork;
import org.apache.hadoop.hive.ql.plan.ReduceWork;
import org.apache.hadoop.hive.ql.plan.SparkEdgeProperty;
import org.apache.hadoop.hive.ql.plan.SparkWork;
import org.apache.hadoop.hive.ql.plan.UnionWork;
import org.apache.hadoop.hive.ql.stats.StatsFactory;
import org.apache.hadoop.hive.ql.stats.StatsPublisher;
import org.apache.hadoop.hive.shims.ShimLoader;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapred.JobConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;

public class SparkPlanGenerator {
  private static final Log LOG = LogFactory.getLog(SparkPlanGenerator.class);

  private JavaSparkContext sc;
  private final JobConf jobConf;
  private Context context;
  private Path scratchDir;
  //used to make sure parents join to the same children, in case of Union and Reduce-side join.
  private Map<BaseWork, SparkTran> childWorkTrans = new HashMap<BaseWork, SparkTran>();

  public SparkPlanGenerator(JavaSparkContext sc, Context context,
      JobConf jobConf, Path scratchDir) {
    this.sc = sc;
    this.context = context;
    this.jobConf = jobConf;
    this.scratchDir = scratchDir;
  }

  public SparkPlan generate(SparkWork sparkWork) throws Exception {
    SparkPlan plan = new SparkPlan();
    GraphTran trans = new GraphTran();
    Set<BaseWork> roots = sparkWork.getRoots();
    for (BaseWork w : roots) {
      if (!(w instanceof MapWork)) {
        throw new Exception(
            "The roots in the SparkWork must be MapWork instances!");
      }
      MapWork mapWork = (MapWork) w;
      JobConf newJobConf = cloneJobConf(mapWork);
      SparkTran tran = null;
      Operator<? extends OperatorDesc> topOp = (Operator<? extends OperatorDesc>)
          (mapWork.getAllRootOperators().toArray()[0]);
      if (isOpTreeSplit(topOp)) {
        // Split the MapWork into three MapWorks
        // TODO: Assuming the split is two way. Need to generalize for n-split

        // Identify the operator which has the split (or more than one children)
        Operator<? extends OperatorDesc> splitOp = topOp;
        while (splitOp.getChildOperators().size() <= 1) {
          splitOp = splitOp.getChildOperators().get(0);
        }

        // TODO: NULL checks on splitOp

        List<Operator<?>> children = Utilities.cloneOperatorTree(newJobConf, splitOp.getChildOperators());

        splitOp.setChildOperators(null);
        for(Operator<?> childOp : children) {
          childOp.setParentOperators(null);
        }

        // Now create a MapWork that contains the operator tree up to "splitOp"
        tran = generate(cloneJobConf(mapWork), mapWork);
        JavaPairRDD<BytesWritable, BytesWritable> input = generateRDD(newJobConf, mapWork);
        trans.addRootTranWithInput(tran, input);

        // Create two MapWorks from "children" of "splitOp"
        MapWork childMapWork1 = Utilities.getMapWork(newJobConf);
        Map<String, Operator<? extends OperatorDesc>> aliasToWork1 = childMapWork1.getAliasToWork();
        for(Entry<String, Operator<? extends OperatorDesc>> entry : aliasToWork1.entrySet()) {
          entry.setValue(children.get(0));
        }
        childMapWork1.setIsMapWithoutTS(true);

        MapTran childTran1 = generate(cloneJobConf(childMapWork1), childMapWork1);
        trans.connect(tran, childTran1);

        MapWork childMapWork2 = Utilities.getMapWork(newJobConf);
        Map<String, Operator<? extends OperatorDesc>> aliasToWork2 = childMapWork2.getAliasToWork();
        for(Entry<String, Operator<? extends OperatorDesc>> entry : aliasToWork2.entrySet()) {
          entry.setValue(children.get(1));
        }
        childMapWork2.setIsMapWithoutTS(true);

        MapTran childTran2 = generate(cloneJobConf(childMapWork2), childMapWork2);
        trans.connect(tran, childTran2);
      } else {
        tran = generate(newJobConf, mapWork);
        JavaPairRDD<BytesWritable, BytesWritable> input = generateRDD(newJobConf, mapWork);
        trans.addRootTranWithInput(tran, input);
      }

      while (sparkWork.getChildren(w).size() > 0) {
        BaseWork child = sparkWork.getChildren(w).get(0);
        SparkTran childTran = childWorkTrans.get(child);
        if (child instanceof ReduceWork) {
          ReduceTran rt = null;
          if (((ReduceWork) child).getReducer() instanceof JoinOperator) {
            // Reduce-side join operator: The strategy to insert a UnionTran (UT) to union the output
            // of the two separate input map-trans (MT), which are then shuffled to the appropriate partition
            // for the ReduceTran (RT).

            // Before:    MW   MW
            //             \   /
            //              RW (JoinOperator)

            // After:     MT   MT
            //             \   /
            //              UT
            //              |
            //              RT (JoinOperator)
            if (childTran == null) {
              //create a new UT, and put it in the map.
              rt = generateRTWithEdge(sparkWork, w, child);
              UnionTran ut = generateUnionTran();
              trans.connect(tran, ut);
              trans.connect(ut, rt);
              childWorkTrans.put(child, ut);
            } else {
              //already a UT in the map, connect
              trans.connect(tran, childTran);
              break;
            }
          } else {
            rt = generateRTWithEdge(sparkWork, w, child);
            trans.connect(tran, rt);
          }
          w = child;
          tran = rt;
        } else if (child instanceof UnionWork) {
          if (childTran == null) {
            SparkTran ut = generateUnionTran();
            childWorkTrans.put(child, ut);
            trans.connect(tran, ut);
            w = child;
            tran = ut;
          } else {
            trans.connect(tran, childTran);
            break;
          }
        }
      }
    }
    childWorkTrans.clear();
    plan.setTran(trans);
    return plan;
  }

  private static boolean isOpTreeSplit(Operator<? extends OperatorDesc> topOp) {
    Operator<? extends OperatorDesc> op = topOp;
    while (op != null) {
      List<Operator<? extends OperatorDesc>> children = op.getChildOperators();
      if (children.size() > 1) {
        return true;
      } else if (children.size() == 1) {
        op = children.get(0);
      } else {
        op = null;
      }
    }

    return false;
  }

  private ReduceTran generateRTWithEdge(SparkWork sparkWork, BaseWork parent, BaseWork child) throws Exception {
    SparkEdgeProperty edge = sparkWork.getEdgeProperty(parent, child);
    SparkShuffler st = generate(edge);
    ReduceTran rt = generate((ReduceWork) child);
    rt.setShuffler(st);
    rt.setNumPartitions(edge.getNumPartitions());
    return rt;
  }

  private JavaPairRDD<BytesWritable, BytesWritable> generateRDD(JobConf jobConf, MapWork mapWork)
      throws Exception {
    Class ifClass = getInputFormat(mapWork);

    return sc.hadoopRDD(jobConf, ifClass, WritableComparable.class,
        Writable.class);
  }

  private Class getInputFormat(MapWork mWork) throws HiveException {
    if (mWork.getInputformat() != null) {
      HiveConf.setVar(jobConf, HiveConf.ConfVars.HIVEINPUTFORMAT,
          mWork.getInputformat());
    }
    String inpFormat = HiveConf.getVar(jobConf,
        HiveConf.ConfVars.HIVEINPUTFORMAT);
    if ((inpFormat == null) || (StringUtils.isBlank(inpFormat))) {
      inpFormat = ShimLoader.getHadoopShims().getInputFormatClassName();
    }

    if (mWork.isUseBucketizedHiveInputFormat()) {
      inpFormat = BucketizedHiveInputFormat.class.getName();
    }

    Class inputFormatClass;
    try {
      inputFormatClass = Class.forName(inpFormat);
    } catch (ClassNotFoundException e) {
      String message = "Failed to load specified input format class:"
          + inpFormat;
      LOG.error(message, e);
      throw new HiveException(message, e);
    }

    return inputFormatClass;
  }

  private MapTran generate(JobConf jobConf, MapWork mw) throws Exception {
    initStatsPublisher(mw);
    MapTran result = new MapTran();
    byte[] confBytes = KryoSerializer.serializeJobConf(jobConf);
    HiveMapFunction mapFunc = new HiveMapFunction(confBytes);
    result.setMapFunction(mapFunc);
    return result;
  }

  private SparkShuffler generate(SparkEdgeProperty edge) {
    Preconditions.checkArgument(!edge.isShuffleNone(),
        "AssertionError: SHUFFLE_NONE should only be used for UnionWork.");
    if (edge.isShuffleSort()) {
      return new SortByShuffler();
    }
    return new GroupByShuffler();
  }

  private ReduceTran generate(ReduceWork rw) throws Exception {
    ReduceTran result = new ReduceTran();
    JobConf newJobConf = cloneJobConf(rw);
    byte[] confBytes = KryoSerializer.serializeJobConf(newJobConf);
    HiveReduceFunction redFunc = new HiveReduceFunction(confBytes);
    result.setReduceFunction(redFunc);
    return result;
  }

  private UnionTran generateUnionTran() {
    UnionTran result = new UnionTran();
    return result;
  }

  private JobConf cloneJobConf(BaseWork work) throws Exception {
    JobConf cloned = new JobConf(jobConf);
    // Make sure we'll use a different plan path from the original one
    HiveConf.setVar(cloned, HiveConf.ConfVars.PLAN, "");
    try {
      cloned.setPartitionerClass((Class<? extends Partitioner>) (Class.forName(HiveConf.getVar(cloned,
        HiveConf.ConfVars.HIVEPARTITIONER))));
    } catch (ClassNotFoundException e) {
      String msg = "Could not find partitioner class: " + e.getMessage() + " which is specified by: " +
        HiveConf.ConfVars.HIVEPARTITIONER.varname;
      throw new IllegalArgumentException(msg, e);
    }
    if (work instanceof MapWork) {
      List<Path> inputPaths = Utilities.getInputPaths(cloned, (MapWork) work, scratchDir, context, false);
      Utilities.setInputPaths(cloned, inputPaths);
      Utilities.setMapWork(cloned, (MapWork) work, scratchDir, false);
      Utilities.createTmpDirs(cloned, (MapWork) work);
      cloned.set("mapred.mapper.class", ExecMapper.class.getName());
    } else if (work instanceof ReduceWork) {
      Utilities.setReduceWork(cloned, (ReduceWork) work, scratchDir, false);
      Utilities.createTmpDirs(cloned, (ReduceWork) work);
      cloned.set("mapred.reducer.class", ExecReducer.class.getName());
    }
    return cloned;
  }

  private void initStatsPublisher(BaseWork work) throws HiveException {
    // initialize stats publisher if necessary
    if (work.isGatheringStats()) {
      StatsPublisher statsPublisher;
      StatsFactory factory = StatsFactory.newFactory(jobConf);
      if (factory != null) {
        statsPublisher = factory.getStatsPublisher();
        if (!statsPublisher.init(jobConf)) { // creating stats table if not exists
          if (HiveConf.getBoolVar(jobConf, HiveConf.ConfVars.HIVE_STATS_RELIABLE)) {
            throw new HiveException(
                ErrorMsg.STATSPUBLISHER_INITIALIZATION_ERROR.getErrorCodedMsg());
          }
        }
      }
    }
  }

}
