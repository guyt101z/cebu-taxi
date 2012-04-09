package org.openplans.cebutaxi.inference.impl;

import gov.sandia.cognition.math.matrix.Matrix;
import gov.sandia.cognition.math.matrix.MatrixFactory;
import gov.sandia.cognition.math.matrix.Vector;
import gov.sandia.cognition.math.matrix.VectorFactory;
import gov.sandia.cognition.math.matrix.mtj.DenseMatrix;
import gov.sandia.cognition.math.matrix.mtj.decomposition.EigenDecompositionRightMTJ;
import gov.sandia.cognition.math.matrix.mtj.decomposition.SingularValueDecompositionMTJ;
import gov.sandia.cognition.math.signals.LinearDynamicalSystem;
import gov.sandia.cognition.statistics.bayesian.KalmanFilter;
import gov.sandia.cognition.statistics.distribution.MultivariateGaussian;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.apache.log4j.Logger;
import org.geotools.geometry.jts.JTS;
import org.geotools.geometry.jts.JTSFactoryFinder;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultEngineeringCRS;
import org.geotools.referencing.crs.DefaultGeocentricCRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.geotools.referencing.cs.DefaultAffineCS;
import org.opengis.geometry.MismatchedDimensionException;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CRSAuthorityFactory;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;
import org.opentripplanner.graph_builder.impl.map.StreetMatcher;
import org.opentripplanner.model.GraphBundle;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.core.TraverseOptions;
import org.opentripplanner.routing.edgetype.StreetEdge;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.impl.GraphServiceImpl;
import org.opentripplanner.routing.impl.StreetVertexIndexServiceImpl;
import org.opentripplanner.routing.location.StreetLocation;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.CoordinateSequence;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Point;

import au.com.bytecode.opencsv.CSVReader;

public class GraphTest {

  private static final org.apache.log4j.Logger log = Logger.getLogger(GraphTest.class);
  private static final double gVariance = 50d;
  private static final double aVariance = 25d;
  private static final long avgTimeDiff = 1;
  private static final double initialAngularRate = Math.PI/2d;

  public static void main(String[] args) {
    System.setProperty("org.geotools.referencing.forceXY", "true");
    GraphServiceImpl gs = new GraphServiceImpl();
    GraphBundle bundle = new GraphBundle(new File("src/main/resources/org/openplans/cebutaxi/"));
    
    gs.setBundle(bundle);
    gs.refreshGraph();
    Graph graph = gs.getGraph();
    StreetMatcher streetMatcher = new StreetMatcher(graph);
    
    StreetVertexIndexServiceImpl indexService = new StreetVertexIndexServiceImpl(graph);
    indexService.setup();
    
    TraverseOptions options = new TraverseOptions(TraverseMode.CAR);
    
    SimpleDateFormat sdf = new SimpleDateFormat("F/d/y H:m:s");
    
    StandardTrackingFilter filter = new StandardTrackingFilter(gVariance, aVariance);
    MultivariateGaussian belief = null;
    
    final CSVReader gps_reader;
    final FileWriter test_output;
    try {
      String googleWebMercatorCode = "EPSG:4326";
      
      String cartesianCode = "EPSG:4499";
       
      CRSAuthorityFactory crsAuthorityFactory = CRS.getAuthorityFactory(true);
       
      CoordinateReferenceSystem mapCRS = crsAuthorityFactory.createCoordinateReferenceSystem(googleWebMercatorCode);
       
      CoordinateReferenceSystem dataCRS = crsAuthorityFactory.createCoordinateReferenceSystem(cartesianCode);
                             
      boolean lenient = true; // allow for some error due to different datums
      MathTransform transform = CRS.findMathTransform(mapCRS, dataCRS, lenient);
      
      test_output = new FileWriter("src/main/resources/org/openplans/cebutaxi/test_data/test_output.txt"); 
      test_output.write("time,original_lat,original_lon,kfMean_lat,kfMean_lon,kfMajor_lat,kfMajor_lon,kfMinor_lat,kfMinor_lon,graph_segment_id\n");
      
      
      gps_reader = new CSVReader(
//          new FileReader("src/main/resources/org/openplans/cebutaxi/test_data/Cebu-Taxi-GPS/Day4-Taxi-1410-2101.txt"), '\t');
          new FileReader("src/main/resources/org/openplans/cebutaxi/test_data/Cebu-Taxi-GPS/0726.csv"), ',');
      String[] nextLine;
      gps_reader.readNext();
      log.info("processing gps data");
      
      long prevTime = 0;
  
      Coordinate prevObsCoords = null;
      Matrix O = StandardTrackingFilter.getObservationMatrix();
      
      while ((nextLine = gps_reader.readNext()) != null) {
        
        StringBuilder sb = new StringBuilder();
        
//        Date datetime = sdf.parse(nextLine[0] + " " + nextLine[1]);
        Date datetime = sdf.parse(nextLine[0]);
        long timeDiff = prevTime == 0 ? 0 : (datetime.getTime() - prevTime)/1000;
        prevTime = datetime.getTime();
        
        log.info("processing record time " + datetime.toString());
        
        double lat = Double.parseDouble(nextLine[2]);
        double lon = Double.parseDouble(nextLine[3]);
        
        sb.append(datetime.getTime()).append(",");
        sb.append(lat).append(",").append(lon).append(",");
        
        /*
         * Transform gps observation to cartesian coordinates
         */
        Coordinate obsCoords = new Coordinate(lon, lat);
        Coordinate obsPoint = new Coordinate();
        JTS.transform(obsCoords, obsPoint, transform);
        
        Vector xyPoint = VectorFactory.getDefault().createVector2D(obsPoint.x, obsPoint.y);
        
        /*
         * Update the motion filter
         */
        final DenseMatrix covar;
        final Vector infMean;
        
        belief = updateFilter(timeDiff, xyPoint, filter, belief);
        if (timeDiff > 0) {
//          filter.measure(belief, xyPoint);
//          filter.predict(belief);
          filter.update(belief, xyPoint);
          
          
          infMean = O.times(belief.getMean().clone());
          covar = (DenseMatrix) O.times(belief.getCovariance().times(O.transpose()));
        } else {
          covar = (DenseMatrix) O.times(belief.getCovariance().times(O.transpose()));
          infMean = O.times(belief.getMean());
        }
        
        
        final EigenDecompositionRightMTJ decomp = EigenDecompositionRightMTJ.create(covar);
        Matrix Shalf = MatrixFactory.getDefault().createIdentity(2, 2);
        Shalf.setElement(0, 0, Math.sqrt(decomp.getEigenValue(0).getRealPart()));
        Shalf.setElement(1, 1, Math.sqrt(decomp.getEigenValue(1).getRealPart()));
        Vector majorAxis = infMean.plus(decomp.getEigenVectorsRealPart().times(Shalf).scale(1.98).getColumn(0));
        Vector minorAxis = infMean.plus(decomp.getEigenVectorsRealPart().times(Shalf).scale(1.98).getColumn(1));
        
        /*
         * Transform state mean position coordinates to lat, lon
         */
        Coordinate kfMean= new Coordinate();
        JTS.transform(new Coordinate(infMean.getElement(0), infMean.getElement(1)), kfMean, transform.inverse());
        sb.append(kfMean.y).append(",").append(kfMean.x).append(",");
        Coordinate kfMajor = new Coordinate();
        JTS.transform(new Coordinate(majorAxis.getElement(0), majorAxis.getElement(1)), kfMajor, transform.inverse());
        sb.append(kfMajor.y).append(",").append(kfMajor.x).append(",");
        Coordinate kfMinor = new Coordinate();
        JTS.transform(new Coordinate(minorAxis.getElement(0), minorAxis.getElement(1)), kfMinor, transform.inverse());
        sb.append(kfMinor.y).append(",").append(kfMinor.x).append(",");

        log.info("filter belief=" + belief.toString());
        
        log.info("attempting snap to graph for point " + obsCoords.toString());
        /*
         * Snap to graph
         */
        Vertex snappedVertex =  indexService.getClosestVertex(obsCoords, null, options);
        if (snappedVertex != null
            && (snappedVertex instanceof StreetLocation)) {
          StreetLocation snappedStreetLocation =  (StreetLocation)snappedVertex;
          double dist = snappedVertex.distance(obsCoords);
          
          log.info("distance to graph: " + dist);
          log.info("vertexLabel=" + snappedVertex.getLabel());
          log.info("streetLocationName=" + snappedStreetLocation.getName());
          
//          List<StreetEdge> edges = Objects.firstNonNull(snappedStreetLocation.getSourceEdges(),
//              ImmutableList.<StreetEdge>of());
          
          Set<Integer> ids = Sets.newHashSet();
          if (prevObsCoords != null && !prevObsCoords.equals2D(obsCoords)) {
            CoordinateSequence movementSeq = JTSFactoryFinder.getGeometryFactory().getCoordinateSequenceFactory()
                .create(new Coordinate[] {prevObsCoords, obsCoords});
            Geometry movementGeometry = JTSFactoryFinder.getGeometryFactory().createLineString(movementSeq);
            List<Edge> minimumConnectingEdges = streetMatcher.match(movementGeometry);
                
            for (Edge edge : Objects.firstNonNull(minimumConnectingEdges, ImmutableList.<Edge>of())) {
              Integer edgeId = graph.getIdForEdge(edge);
              if (edgeId != null)
                ids.add(edgeId);
            }
          } else {
            
            for (Edge edge : Objects.firstNonNull(snappedStreetLocation.getOutgoingStreetEdges(), 
                ImmutableList.<Edge>of())) {
              Integer edgeId = graph.getIdForEdge(edge);
              if (edgeId != null)
                ids.add(edgeId);
            }
            
          }
          sb.append("\"" + ids.toString() + "\"");
        } else {
          sb.append("NA");
        }
        
        prevObsCoords = obsCoords;
        test_output.write(sb.toString() + "\n");
      }
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    } catch (ParseException e) {
      e.printStackTrace();
    } catch (FactoryException e) {
      e.printStackTrace();
    } catch (MismatchedDimensionException e) {
      e.printStackTrace();
    } catch (TransformException e) {
      e.printStackTrace();
    }
  }

  private static MultivariateGaussian updateFilter(long timeDiff, Vector xyPoint, 
      StandardTrackingFilter filter, MultivariateGaussian belief) {
    /*
     * Initialize or update the kalman filter
     */
    if (belief == null) {
      belief = filter.createInitialLearnedObject();
      belief.setMean(VectorFactory.getDefault().copyArray(new double[]{xyPoint.getElement(0), 0d,
          xyPoint.getElement(1), 0d}));
    } else {
      
      /*
       * We need to update the time-dependent components of this linear system
       * when time differences are non-constant.
       */
//      log.info("timeDiff (s)=" + timeDiff);
//      
//      Matrix modelCovariance = createStateCovariance(timeDiff/60d);
//      filter.setModelCovariance(modelCovariance);      
//      
//      Matrix Gct = createStateTransitionMatrix(belief, timeDiff/60d);
//      Matrix G = MatrixFactory.getDefault().createIdentity(5, 5);
//      G.setSubMatrix(0, 0, Gct);
//      filter.getModel().setA(G);
      
    }
    return belief;
  }

}
