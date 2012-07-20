package controllers;

import play.*;
import play.mvc.*;

import inference.InferenceService;

import java.io.IOException;
import java.io.StringWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

import org.openplans.tools.tracking.impl.Observation;
import org.openplans.tools.tracking.impl.util.OtpGraph;

import org.apache.commons.io.IOUtils;

import api.AuthResponse;
import api.MessageResponse;

import com.vividsolutions.jts.geom.Coordinate;

import jobs.ObservationHandler;

import models.*;

public class Api extends Controller {

	static SimpleDateFormat locationDateFormat = new SimpleDateFormat("yyyyMMdd HHmmss");
	
	public static final SimpleDateFormat sdf = new SimpleDateFormat(
		      "yyyy-MM-dd hh:mm:ss");
	
	public static OtpGraph graph = new OtpGraph(
		      Play.configuration.getProperty("application.otpGraphPath"));
	
	public static OtpGraph getGraph() {
		return graph;
	}
		
	private static List<Long> ConvetStringArrayToLongArray(String[] stringArray){
		ArrayList<Long> longList = new ArrayList<Long>();

		for(String str : stringArray){
		longList.add(new Long(str));
		}

		return longList;
		}
	
	public static void alerts(String imei, String type, String ids) {
		
		List<Alert> alerts = null;
		
		// TODO IMEI filtering for dispatch messages -- not useful for testing
		
		if(ids != null && !ids.isEmpty())
		{
			String[] id_list = ids.split(",");			

			alerts = Alert.em().createQuery("FROM Alert alert WHERE alert.id in (?1)").setParameter(1, ConvetStringArrayToLongArray(id_list)).getResultList(); 
		}
		else if(type == null || type.isEmpty() || type.toLowerCase().equals("all"))
			alerts = Alert.all().fetch();
		else
			alerts = Alert.find("type = ?", type.toLowerCase()).fetch();
			
		if(request.format == "xml")
			renderXml(alerts);
		else
			renderJSON(alerts);
	}
	
	public static void activeTaxis() {
			
		Calendar cal = Calendar.getInstance();
		cal.add(Calendar.MINUTE, -5);
		Date recentDate = cal.getTime();
		
		List<Phone> phones = Phone.find("lastUpdate > ?", recentDate).fetch();
			
		if(request.format == "xml")
			renderXml(phones);
		else
			renderJSON(phones);
	}
	
	
	public static void messages(String imei, Long message_id, Double lat, Double lon, String body) {
		
		if(request.method == "POST")
		{
			Phone phone = Phone.find("imei = ?", imei).first();
			
			if(phone != null)
			{
				Message message = new Message();
				
				// TODO message_id lookup for threading -- not useful for testing 
				
				message.read = false;
				
				message.fromPhone = phone;
				
				// TODO SimpleDateFormat df = new SimpleDateFormat("yyyy-mm-dd hh:MM:SS");
				
				message.timestamp = new Date();
				
				
				message.location_lat = lat;
				message.location_lon = lon;
				message.body = body;
				
				message.save();
			}
			else
			{
				Logger.info("Unknown phone entry for IMEI " + imei); 
				unauthorized("Unknown Phone IMEI");
			}
			
		}
		else
		{
			// TODO IMEI message lookup -- not useful for testing
		
			List<Message> messages = Message.find("fromphone_id IS NULL").fetch();
			
			List<MessageResponse> messageResponses = new ArrayList<MessageResponse>();
			
			for(Message message : messages)
			{
				messageResponses.add(new MessageResponse(message));
			}
			
			// TODO mark messages read -- not useful for testing
		
			if(request.format == "xml")
				renderXml(messageResponses);
			else
				renderJSON(messageResponses);
		}
	}
	
	public static void operator(String imei)
	{
		Logger.info("Operator Auth request for IMEI " + imei); 
		
		if(imei == null)
			unauthorized("IMEI Required");
		
		Phone phone = Phone.find("imei = ?", imei).first();
		
		if(phone != null)
		{
			AuthResponse authResponse = new AuthResponse();
			
			authResponse.id = new Long(100);
			authResponse.name = phone.operator.name;
			
			if(phone.driver != null)
			{
				authResponse.driverId = phone.driver.driverId;
				authResponse.driverName = phone.driver.name;
			}
			
			if(phone.vehicle != null)
			{
				authResponse.bodyNumber = phone.vehicle.bodyNumber;
			}
			
			authResponse.gpsInterval = 5;
			authResponse.updateInterval = 30;
			
			if(request.format == "xml")
				renderXml(authResponse);
			else
				renderJSON(authResponse);
		}
		else
		{
			Logger.info("Unknown phone entry for IMEI " + imei); 
			unauthorized("Unknown Phone IMEI");
		}
	}
	
	public static void register(String imei, Long operator)
	{
		if(imei != null && !imei.isEmpty() && operator != null)
		{
			Phone phone = Phone.find("imei = ?", imei).first();
			
			if(phone == null)
			{
				Logger.info("Creating phone entry for IMEI " + imei); 
				phone = new Phone();
				phone.imei = imei;
			}
			
			Operator operatorObj = Operator.findById(operator);
			
			if(operatorObj == null)
			{
				Logger.info("Unknown operator: " + operator); 
				badRequest();
			}
			
			phone.operator = operatorObj;
			
			phone.save();
			
			ok();
		}
		else
		{
			List<Operator> operators = Operator.findAll();
			
			if(request.format == "xml")
				renderXml(operators);
			else
				renderJSON(operators);
		}
	}
	
	public static void login(String imei, String driver, String body)
	{
		if(imei == null)
			unauthorized("IMEI Required");
		
		Phone phone = Phone.find("imei = ?", imei).first();
		
		if(phone == null)
		{
			Logger.info("Unknown phone entry for IMEI " + imei); 
			unauthorized("Unknown Phone IMEI");
		}
		
		if(driver == null)
			badRequest();
		
		Driver driverObj = Driver.find("driverId = ?", driver).first();
		
		if(driverObj == null)
		{
			Logger.info("Unknown Driver Id " + driver); 
			
			driverObj = new Driver();
			driverObj.driverId = driver;
			driverObj.save();
		
		}
		
		if(body == null)
			badRequest();
		
		Vehicle veichie = Vehicle.find("bodyNumber = ?", body).first();
		
		if(veichie == null)
		{
			Logger.info("Unknown vehicle, createing record for body number " + body); 
			
			veichie = new Vehicle();
			veichie.bodyNumber = body;
			veichie.save();
		}
		
		phone.driver = driverObj;
		phone.vehicle = veichie;
		
		phone.save();

		AuthResponse authResponse = new AuthResponse();
		
		authResponse.id = new Long(100);
		authResponse.name = phone.operator.name;
		
		if(phone.driver != null)
		{
			authResponse.driverId = phone.driver.driverId;
			authResponse.driverName = phone.driver.name;
		}
		
		if(phone.vehicle != null)
		{
			authResponse.bodyNumber = phone.vehicle.bodyNumber;
		}
		
		authResponse.gpsInterval = 5;
		authResponse.updateInterval = 30;
		
		if(request.format == "xml")
			renderXml(authResponse);
		else
			renderJSON(authResponse);
	}
	
	public static void logout(String imei)
	{
		if(imei == null)
			unauthorized("IMEI Required");
		
		Phone phone = Phone.find("imei = ?", imei).first();
		
		if(phone == null)
		{
			Logger.info("Unknown phone entry for IMEI " + imei); 
			unauthorized("Unknown Phone IMEI");
		}
		
		phone.driver = null;
		phone.vehicle = null;
		
		phone.save();

		ok();
	}
	
	
    public static void location(String imei, String content) throws IOException {
    
    	// test request via curl:
    	// 
    	// curl -d "20120430T133023,124.02342,34.43622,8.33,124,200" http://localhost:9000/api/location?imei=myIMEI    	
    	
		// check for valid request
    	
    	Date now = new Date();
		
    	if(imei == null || imei.trim().isEmpty())
    		badRequest();
    
   	
    	// copy POST body to string

    	String requestBody = null;
    	String message = "";
    	
		if(content != null)
		{
			requestBody = content;
			
		}
		else if(request.method == "POST")
        {
              requestBody = params.get("body");
        }
		
		message = "location message received: imei=" + imei + " " + content;
    	
    	if(requestBody == null || requestBody.isEmpty())
    		badRequest();
    		
    	// requests can contain multiple requests, split on newline
    	
    	String[] lines = requestBody.split("\n");
    	 	
    	ArrayList<Observation> observations = new ArrayList<Observation>();
    	
    	for(String line : lines)
    	{
    		// request format: 20120430T133023,124.02342,34.43622,8.33,124,200
    		
    		String[] lineParts = line.trim().split(",");
    		
    		
    		if(lineParts.length != 6)
    			badRequest();
    	
    		try
    		{
	    		Date dateTime = locationDateFormat.parse(lineParts[0].replace("T", " "));
	    		Double lat = Double.parseDouble(lineParts[1]);
	    		Double lon = Double.parseDouble(lineParts[2]);
	    		Double velocity = Double.parseDouble(lineParts[3]);
	    		Double heading = Double.parseDouble(lineParts[4]);
	    		Double gpsError = Double.parseDouble(lineParts[5]);
	    		
	    		Observation observation = Observation.createObservation(imei, dateTime, new Coordinate(lat, lon), velocity, heading, gpsError);
	    			    	
	    		observations.add(observation);
	    	
	    		// using a local queue to handle logging/inference...for now.
	    		ObservationHandler.addObservation(observation, now.toString() + " - " + imei +  " - " + line);	    		
	   		
    		}
    		catch(Exception e)
    		{
    			Logger.error("Bad location update string: ", line);
    			
    			// couldn't parse results
    			badRequest();
    		}
    	}
    	
    	if(observations.size() > 0)
    	{
    		Observation observation = observations.get(observations.size() -1 );
    		
    		Phone phone = Phone.find("imei = ?", observation.getVehicleId()).first();
    		
    		if(phone != null)
    		{
    			phone.recentLat = observation.getObsCoordsLatLon().x;
    			phone.recentLon = observation.getObsCoordsLatLon().y;
    			phone.lastUpdate = new Date();
    			
    			phone.save();
    		}
    	}
    	
    	try
    	{
    		InferenceService.processRecords(observations, InferenceService.INFO_LEVEL.SINGLE_RESULT);
    	}
    	catch(Exception e)
		{
			Logger.error("Unable to process records: ", e.toString());
		}
    	
        ok();
    }

}
