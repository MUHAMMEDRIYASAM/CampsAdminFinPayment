package com.splenta.admin.ad_process;
import java.sql.CallableStatement;
import java.sql.SQLException;
import java.util.Map;

import org.apache.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.client.kernel.BaseActionHandler;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;




public class FinLogUpdate extends BaseActionHandler {
private static Logger log = Logger.getLogger(FinLogUpdate.class);
@Override
protected JSONObject execute(Map<String, Object> parameters, String content) {
		
	try {
		OBContext.setAdminMode();
		JSONObject jsonRequest = new JSONObject(content);
		JSONObject jsonResponse = new JSONObject();
		JSONArray respActions = new JSONArray();
		JSONObject refreshGrid = new JSONObject();
		final JSONObject msg = new JSONObject();
		
		try {
			
			CallableStatement process = OBDal.getInstance().getConnection()
					.prepareCall("{call AP_ADMIN_LOG(?)}");
			process.setString(1, "TEST");
			
			process.execute();
			OBDal.getInstance().flush();
			log.info("Status :  Success");
			msg.put("severity", "success");
			msg.put("text", "Success");
		} catch (SQLException e) {
			OBDal.getInstance().flush();
			log.info("Status :  Failed");
			msg.put("severity", "error");
			msg.put("text", "SQL Exception");
			e.printStackTrace();
		}	
		refreshGrid.put("refreshGrid", new JSONObject());
		respActions.put(refreshGrid);
		jsonResponse.put("responseActions", respActions);
		jsonResponse.put("message", msg);
		return jsonResponse;
	} catch (Exception e) {
		try {
			JSONObject msg = new JSONObject();
			msg.put("severity", "error");
			msg.put("text", e.getMessage());

			return new JSONObject().put("message", msg);
		} catch (JSONException e1) {
			// TODO Auto-generated catch block
			return new JSONObject();
		}
	} finally {
		OBContext.restorePreviousMode();
	}
	}
	
	
	
}



