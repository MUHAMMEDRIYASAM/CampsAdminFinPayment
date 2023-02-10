package com.splenta.admin.ad_process.bulkprocesses;

import java.util.Map;

import org.apache.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.client.kernel.BaseActionHandler;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.assetmgmt.Asset;

import com.splenta.admin.BulkProcess;

public class BulkProcessReject extends BaseActionHandler {
	private static final Logger log = Logger.getLogger(BulkProcessReject.class);

	@Override
	protected JSONObject execute(Map<String, Object> parameters, String content) {
		OBContext.setAdminMode();
		JSONObject jsonRequest, result;
		result = new JSONObject();
		JSONObject refreshCurrentRecord = new JSONObject();
		JSONObject refreshGrid = new JSONObject();
		JSONArray actions = new JSONArray();
		String bulkReqId, Notes;
		BulkProcess bulkRecord = null;
		try {
			log.info("Rejecting in Progress...");
			jsonRequest = new JSONObject(content);
			bulkReqId = jsonRequest.getString("inpapBulkprocessId");
			JSONObject params = jsonRequest.getJSONObject("_params");
			Notes = params.getString("rejectNote");
			bulkRecord = OBDal.getInstance().get(BulkProcess.class, bulkReqId);
			try {
				OBCriteria<Asset> assetList = OBDal.getInstance().createCriteria(Asset.class);
				assetList.add(Restrictions.like(Asset.PROPERTY_HELPCOMMENT, "BLK%"));
				log.info("Unmarking " + assetList.list().size() + " assets.");				
				for (Asset ast : assetList.list()) {
					ast.setHelpComment(null);
					ast.setAmIsdisprocessing(false);
				}
				OBDal.getInstance().flush();
			} catch (Exception e) {
				log.info("No Marked Assets found.");
				e.printStackTrace();
			}
			bulkRecord.setDocStatus("RJD");
			bulkRecord.setDocAction("VL");
			bulkRecord.setDescription(Notes);
			OBDal.getInstance().save(bulkRecord);
			JSONObject msgTotal = new JSONObject();
			msgTotal.put("msgType", "success");
			msgTotal.put("msgTitle", bulkRecord.getDocNumber() + " - Rejected Successfully.");
			JSONObject msgTotalAction = new JSONObject();
			msgTotalAction.put("showMsgInProcessView", msgTotal);
			actions.put(msgTotalAction);
			result.put("responseActions", actions);
			refreshCurrentRecord.put("refreshCurrentRecord", new JSONObject());
			actions.put(refreshCurrentRecord);
			refreshGrid.put("refreshGrid", new JSONObject());
			actions.put(refreshGrid);
			log.info("Rejected.");
			return result;
		} catch (JSONException e) {
			log.info("Unhandled exception while Rejecting." + e);
			return null;
		} finally {
			OBContext.restorePreviousMode();
		}
	}
}