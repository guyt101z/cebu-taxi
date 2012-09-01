package models;

import gov.sandia.cognition.collection.ScalarMap;
import gov.sandia.cognition.math.MutableDouble;
import gov.sandia.cognition.math.RingAccumulator;
import gov.sandia.cognition.statistics.DataDistribution;
import inference.InferenceResultRecord;
import inference.InferenceService;
import inference.InferenceService.INFO_LEVEL;
import inference.ResultSet.OffRoadPath;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.log4j.Logger;
import org.openplans.tools.tracking.impl.Observation;
import org.openplans.tools.tracking.impl.VehicleState;
import org.openplans.tools.tracking.impl.VehicleState.VehicleStateInitialParameters;
import org.openplans.tools.tracking.impl.statistics.filters.AbstractVehicleTrackingFilter;
import org.openplans.tools.tracking.impl.statistics.filters.FilterInformation;
import org.openplans.tools.tracking.impl.statistics.filters.VehicleTrackingBootstrapFilter;
import org.openplans.tools.tracking.impl.statistics.filters.VehicleTrackingFilter;
import org.openplans.tools.tracking.impl.statistics.filters.VehicleTrackingPLFilter;
import org.openplans.tools.tracking.impl.util.OtpGraph;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import controllers.Api;
import controllers.Application;

/**
 * This class holds inference data for a particular vehicle
 * 
 * @author bwillard
 * 
 */
public class InferenceInstance {

  final Logger log = Logger.getLogger(InferenceInstance.class);
  final public String vehicleId;

  public int recordsProcessed = 0;

  public long simSeed = 0l;

  public final boolean isSimulation;
  public boolean isEnabled = true;

  private VehicleTrackingFilter<Observation, VehicleState> filter;

  private final Queue<InferenceResultRecord> resultRecords =
      new ConcurrentLinkedQueue<InferenceResultRecord>();

  private DataDistribution<VehicleState> postBelief;
  private DataDistribution<VehicleState> resampleBelief;
  private VehicleState bestState;

  private final VehicleStateInitialParameters initialParameters;

  public int totalRecords = 0;

  private final INFO_LEVEL infoLevel;
  private static final double _maxUpdateIntervalCutoff = 5d * 60d;

  private static OtpGraph inferredGraph = Api.getGraph();

  private final RingAccumulator<MutableDouble> averager =
      new RingAccumulator<MutableDouble>();
  
  private final Class<? extends VehicleTrackingFilter> filterType;
  
  public InferenceInstance(String vehicleId, boolean isSimulation,
    INFO_LEVEL infoLevel, VehicleStateInitialParameters parameters, 
    String filterTypeName) {
    this.initialParameters = parameters;
    this.vehicleId = vehicleId;
    this.isSimulation = isSimulation;
    this.simSeed = parameters.getSeed();
    this.infoLevel = infoLevel;
    this.filterType = Application.getFilters().get(filterTypeName);
  }

  public RingAccumulator<MutableDouble> getAverager() {
    return averager;
  }

  public VehicleState getBestState() {
    return bestState;
  }

  public VehicleTrackingFilter getFilter() {
    return filter;
  }

  public INFO_LEVEL getInfoLevel() {
    return infoLevel;
  }

  public VehicleStateInitialParameters getInitialParameters() {
    return initialParameters;
  }

  public DataDistribution<VehicleState> getPostBelief() {
    return this.postBelief;
  }

  public int getRecordsProcessed() {
    return recordsProcessed;
  }

  public DataDistribution<VehicleState> getResampleBelief() {
    return this.resampleBelief;
  }

  public Collection<InferenceResultRecord> getResultRecords() {
    return Collections.unmodifiableCollection(this.resultRecords);
  }

  public long getSimSeed() {
    return simSeed;
  }

  public int getTotalRecords() {
    return totalRecords;
  }

  public String getVehicleId() {
    return vehicleId;
  }

  public boolean isSimulation() {
    return isSimulation;
  }

  synchronized public void update(Observation obs) {

    if (!shouldProcessUpdate(obs))
      return;
    
    updateFilter(obs);
    this.recordsProcessed++;

    final InferenceResultRecord infResult = InferenceResultRecord.createInferenceResultRecord(obs, this);

    if (infoLevel == INFO_LEVEL.SINGLE_RESULT
        && !this.resultRecords.isEmpty())
      this.resultRecords.poll();

    this.resultRecords.add(infResult);
  }

  synchronized public void update(VehicleState actualState, Observation obs,
    boolean performInference) {

    if (!shouldProcessUpdate(obs))
      return;
    
    if (performInference) {
      updateFilter(obs);
    }

    this.recordsProcessed++;

    final InferenceResultRecord result =
        InferenceResultRecord.createInferenceResultRecord(obs, this,
            actualState, postBelief.getMaxValueKey(), postBelief
                .clone(),
            resampleBelief != null ? resampleBelief.clone() : null);

    if (infoLevel == INFO_LEVEL.SINGLE_RESULT
        && !this.resultRecords.isEmpty())
      this.resultRecords.poll();

    this.resultRecords.add(result);
  }
  
  /**
   * We check basic conditions for processing the update and
   * also consider resetting the filter.
   * 
   */
  private boolean shouldProcessUpdate(Observation obs) {
    if (filter != null) {
      final double timeDiff =
          filter.getLastProcessedTime() == 0 ? 1d
              : (obs.getTimestamp().getTime() - filter.getLastProcessedTime()) / 1000;
  
      if (timeDiff <= 0) {
        return false;
      } else if (timeDiff >= _maxUpdateIntervalCutoff) {
        /*
         * Note: we're not resetting the off-road paths, yet.
         */
        log.warn(" time diff (" + timeDiff + "s) is past update limit (" 
         + _maxUpdateIntervalCutoff + "s).  resetting filter...");
        postBelief = null;
        filter = null;
        return false;
      }
    }
    
    if (!isEnabled || InferenceService.getInferenceInstance(vehicleId) == null)
      return false;
    
    return true;
  }

  synchronized private void updateFilter(Observation obs) {

    final Stopwatch watch = new Stopwatch();
    watch.start();

    if (filter == null || postBelief == null) {

      Constructor<? extends VehicleTrackingFilter> ctor;
      try {
        ctor = filterType.getConstructor(Observation.class, OtpGraph.class,
            VehicleStateInitialParameters.class, Boolean.class);
        filter = ctor.newInstance(obs, inferredGraph, initialParameters,
          infoLevel.compareTo(INFO_LEVEL.DEBUG) >= 0);
        filter.getRandom().setSeed(simSeed);
        postBelief = filter.createInitialLearnedObject();
      } catch (SecurityException e) {
        e.printStackTrace();
      } catch (NoSuchMethodException e) {
        e.printStackTrace();
      } catch (IllegalArgumentException e) {
        e.printStackTrace();
      } catch (InstantiationException e) {
        e.printStackTrace();
      } catch (IllegalAccessException e) {
        e.printStackTrace();
      } catch (InvocationTargetException e) {
        e.printStackTrace();
      }

    } else {
      filter.update(postBelief, obs);
      
      if (infoLevel.compareTo(INFO_LEVEL.DEBUG) >= 0) {
        final FilterInformation filterInfo =
            filter.getFilterInformation(obs);
        resampleBelief =
            filterInfo != null ? filterInfo.getResampleDist() : null;
      }
    }

    watch.stop();
    averager.accumulate(new MutableDouble(watch.elapsedMillis()));

    if (recordsProcessed > 0 && recordsProcessed % 20 == 0)
      log.info("avg. records per sec = " + 1000d
          / this.getAverager().getMean().value);

    if (postBelief != null)
      this.bestState = postBelief.getMaxValueKey();
    
  }

  public static OtpGraph getInferredGraph() {
    return inferredGraph;
  }

  public Class<? extends VehicleTrackingFilter> getFilterType() {
    return filterType;
  }

  private Map<VehicleState, List<OffRoadPath>> stateToOffRoadPaths = Maps.newHashMap();
  
  public Map<VehicleState, List<OffRoadPath>> getStateToOffRoadPaths() {
    return stateToOffRoadPaths;
  }

  public void setStateToOffRoadPaths(
    Map<VehicleState, List<OffRoadPath>> newMap) {
    this.stateToOffRoadPaths = newMap;
  }

}
