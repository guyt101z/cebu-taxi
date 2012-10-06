package models;

import java.math.BigInteger;
import java.util.Date;

import javax.persistence.Entity;
import javax.persistence.EntityManager;
import javax.persistence.ManyToOne;
import javax.persistence.Query;

import org.hibernate.annotations.Type;
import org.openplans.tools.tracking.impl.ObservationData;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Point;

import play.db.jpa.Model;

@Entity
public class LocationUpdate extends Model {
	
	public String imei;
    
	public Date timestamp;
	
    public Double lat;
    public Double lon;
    
    public Boolean panic;
    
    public Double velocity;
    public Double heading;
    public Double gpsError;
    
    @Type(type = "org.hibernatespatial.GeometryUserType")
    public Point shape;
    
    public ObservationData getObservationData()
    {
    	ObservationData obsData = new ObservationData(this.imei, this.timestamp, new Coordinate(this.lat, this.lon), this.velocity, this.heading, this.gpsError);
    	
    	return obsData;
    }
    
    static public void natveInsert(EntityManager em, ObservationData obs)
    {
    	Query idQuery = em().createNativeQuery("SELECT NEXTVAL('hibernate_sequence');");
    	BigInteger nextId = (BigInteger)idQuery.getSingleResult();
    	
    	em.createNativeQuery("INSERT INTO locationupdate (id, imei, timestamp, lat, lon, velocity, heading, gpserror, shape)" +
    			"  VALUES(?, ?, ?, ?, ?, ?, ?, ?, ST_GeomFromText( ?, 4326));")
    			.setParameter(1,  nextId)
    			.setParameter(2,  obs.getVehicleId())
    			.setParameter(3,  obs.getTimestamp())
    			.setParameter(4,  obs.getObsCoordsLatLon().y)
    			.setParameter(5,  obs.getObsCoordsLatLon().x)
    			.setParameter(6,  obs.getVelocity())
    			.setParameter(7,  obs.getHeading())
    			.setParameter(8,  obs.getAccuracy())
    			.setParameter(9,  "POINT(" + obs.getObsCoordsLatLon().y +  " " + obs.getObsCoordsLatLon().x + ")")
    			.executeUpdate();
    }

}
 
