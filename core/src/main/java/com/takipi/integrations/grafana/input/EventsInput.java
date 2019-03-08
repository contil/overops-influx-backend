package com.takipi.integrations.grafana.input;

import java.util.Arrays;
import java.util.List;

import com.takipi.api.client.util.validation.ValidationUtil.VolumeType;
import com.takipi.integrations.grafana.functions.GrafanaFunction;
import com.takipi.integrations.grafana.util.ArrayUtil;

/**
 * This function populates a table containing information about a list of events. 
 * 
 * Example query:
 * 		events({"fields":"link,type,entry_point,introduced_by,message,
 * 		error_location,stats.hits,rate,first_seen","view":"$view",
 * 		"timeFilter":"$timeFilter","environments":"$environments",
 * 		"applications":"$applications","servers":"$servers","deployments":"$deployments",
 * 		"volumeType":"all","maxColumnLength":80, "types":"$type",
 * 		"pointsWanted":"$pointsWanted","transactions":"$transactions", 
 * 		"searchText":"$search"})

 * Screenshot: https://drive.google.com/file/d/12CVPNc-FDBhWOpofo5Ofatjo-zhHo8qj/view?usp=sharing
 *  
 */
public class EventsInput extends BaseEventVolumeInput {
	
	/**
	 * A comma delimited list of attributes defined in: https://doc.overops.com/reference#get_services-env-id-events-event-id
	 * that can be used to define the columns within the result table. 
	 * Additional available fields described below.
	 */
	public String fields;
	
	public List<String> getFields() {
		String[] array = ArrayUtil.safeSplitArray(fields, GrafanaFunction.ARRAY_SEPERATOR, true);
		return Arrays.asList(array);
	}
	
	/**
	 * A link to the event's ARC analysis
	 */
	public static final String LINK = "link";
	
	/**
	 * A field containing a combination of the event type and message
	 */
	public static final String TYPE_MESSAGE = "typeMessage";
	
	/**
	 * returns 1 if the event has a ticket assigned 
	 */
	public static final String JIRA_STATE = "jira_state";
	
	/**
	 * The event rate hits / invocations
	 */
	public static final String RATE = "rate";
	
	/**
	 * A text description of the event rate
	 */
	public static final String RATE_DESC = "rate_desc";
	
	/**
	 * A text description of the error location, containing optional tier information
	 */
	public static final String DESCRIPTION = "description";
	
	/**
	 * An optional value to control the volume data retrieved for objects in this query. If "hits" is specified,
	 * "N/A" will be returned for all values of the "rate" field (if selected). 
	 */
	public VolumeType volumeType;
	
	/**
	 * An optional value controlling the max string length of the message and typeMessage columns.
	 */
	public int maxColumnLength;
}
