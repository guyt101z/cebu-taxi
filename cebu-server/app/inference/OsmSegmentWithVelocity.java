package inference;

import org.codehaus.jackson.map.annotate.JsonSerialize;

import api.OsmSegment;

import com.vividsolutions.jts.geom.Geometry;

public class OsmSegmentWithVelocity extends OsmSegment {

  private final Double velocity;

  public OsmSegmentWithVelocity(Integer i, Geometry g, String name,
    Double mean) {
    super(i, g, name);
    this.velocity = mean;
  }

  @JsonSerialize
  public Double getVelocity() {
    return velocity;
  }

}
