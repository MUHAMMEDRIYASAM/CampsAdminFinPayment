package com.splenta.admin.ad_process;

import java.io.File;
import java.nio.file.Paths;
import java.util.Map;

import org.apache.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Query;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.client.application.attachment.CoreAttachImplementation;
import org.openbravo.client.kernel.BaseActionHandler;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.financialmgmt.assetmgmt.Asset;

import com.chimera.fixedassetmanagement.ad_process.ErrorMessage;
import com.splenta.admin.BulkProcess;
import com.splenta.admin.ad_process.bulkprocesses.AssetValidations;
import com.splenta.admin.ad_process.bulkprocesses.CSVFileReaderExceptions;
import com.splenta.admin.ad_process.bulkprocesses.CSVUtils;

public class BulkProcessActions extends BaseActionHandler {
	private static final Logger log = Logger.getLogger(BulkProcessActions.class);
	final String sourcepath = OBPropertiesProvider.getInstance().getOpenbravoProperties().getProperty("attach.path");
	CSVUtils csvutils = WeldUtils.getInstanceFromStaticBeanManager(CSVUtils.class);
	AssetValidations validate = WeldUtils.getInstanceFromStaticBeanManager(AssetValidations.class);
	JSONArray actions = new JSONArray();

	@Override
	protected JSONObject execute(Map<String, Object> parameters, String content) {
		try {
			JSONArray action = new JSONArray();

			JSONObject result = new JSONObject();
			JSONObject respMsg = new JSONObject();
			OBContext.setAdminMode();
			JSONObject request = new JSONObject(content);
			if (request.getString("_buttonValue").equals("DONE")) {
				log.warn("content:" + content);
				final String processId = request.getString("inpapBulkprocessId");
				BulkProcess bulkRecord = OBDal.getInstance().getProxy(BulkProcess.class, processId);
				if (bulkRecord != null && bulkRecord.getDocAction() != null) {
					log.warn("DocAction:" + bulkRecord.getDocAction());
					Attachment attachment = new Attachment();
					OBError error = new OBError();
					String attachPath = null, docNo = bulkRecord.getDocNumber();
					if (bulkRecord.getDocAction().equals("VL")) {
						OBError dateCheck = validate.dateAcctCheck(bulkRecord);
						if (dateCheck.getType().equals("Success")) {
							error = validate.validateAttachment(bulkRecord);
							log.info("Line 53: " + error.getType());
							ErrorMessage errMsg = new ErrorMessage();
							if (error.getType().equals("Success")) {
								if (bulkRecord.getDocType().equals("BTR")) {
									String attachDir = CoreAttachImplementation.getAttachmentDirectoryForNewAttachments(
											"2B6510373EDC4A919DF949C07FB8AF3B", bulkRecord.getId());
									attachPath = sourcepath + File.separator + attachDir + File.separator + "DummyFile";
									errMsg = csvutils.readFile(Paths.get(attachPath).normalize(), bulkRecord);
								} else {
									attachment = validate.getAttachment(bulkRecord.getId()).get(0);
									attachPath = sourcepath + File.separator + attachment.getPath() + File.separator
											+ attachment.getName();
									errMsg = csvutils.checkAllFileChecks(Paths.get(attachPath).normalize());
									log.info("Line 66: " + errMsg.isStatus());
									if (errMsg.isStatus()) {
										try {
											OBCriteria<Asset> assetList = OBDal.getInstance()
													.createCriteria(Asset.class);
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
										errMsg = csvutils.readFile(Paths.get(attachPath).normalize(), bulkRecord);
									} else {
										respMsg.put("msgType", "error");
										respMsg.put("msgTitle", errMsg.getMessage());
										respMsg.put("msgText", errMsg.getDescription());
									}
								}
								if (errMsg.isStatus()) {
									bulkRecord.setDocStatus("VLD");
									bulkRecord.setDocAction("FW");
									bulkRecord.setDescription(null);
									setValidated(attachment);
									respMsg.put("msgType", "success");
									respMsg.put("msgTitle", docNo + " - Validated Successfully.");
									respMsg.put("msgText", "Click on the Forward button.");
									OBDal.getInstance().save(bulkRecord);
								} else {
									respMsg.put("msgType", "error");
									respMsg.put("msgTitle", errMsg.getMessage());
									respMsg.put("msgText", errMsg.getDescription());
								}
							} else {
								respMsg.put("msgType", "error");
								respMsg.put("msgTitle", error.getTitle());
								respMsg.put("msgText", error.getMessage());
							}
						} else {
							respMsg.put("msgType", dateCheck.getType());
							respMsg.put("msgTitle", dateCheck.getTitle() + "Update the Accounting Date.");
							respMsg.put("msgText", dateCheck.getMessage());

						}
					} else if (bulkRecord.getDocAction().equals("FW")) {
						OBError dateCheck = validate.dateAcctCheck(bulkRecord);
						if (dateCheck.getType().equals("Success")) {
							error = validate.validateAttachment(bulkRecord);
							if (error.getType().equals("Success")) {
								bulkRecord.setDocStatus("FWD");
								bulkRecord.setDocAction("AP");
								OBDal.getInstance().save(bulkRecord);
								JSONObject refreshCurrentRecord = new JSONObject();
								JSONObject refreshGrid = new JSONObject();
								refreshCurrentRecord.put("refreshCurrentRecord", new JSONObject());
								actions.put(refreshCurrentRecord);
								refreshGrid.put("refreshGrid", new JSONObject());
								actions.put(refreshGrid);
								respMsg.put("msgType", "success");
								respMsg.put("msgTitle", docNo + " - Forwarded Successfully.");
							} else {
								bulkRecord.setDocStatus("DR");
								bulkRecord.setDocAction("VL");
								OBDal.getInstance().save(bulkRecord);
								respMsg.put("msgType", "error");
								respMsg.put("msgTitle", error.getTitle());
								respMsg.put("msgText", "Please attach csv file & Validate again.");
							}
						} else {
							respMsg.put("msgType", dateCheck.getType());
							respMsg.put("msgTitle", dateCheck.getTitle() + "Recreate the request.");
							respMsg.put("msgText", dateCheck.getMessage());
						}
					} else if (bulkRecord.getDocAction().equals("AP")) {
						OBError dateCheck = validate.dateAcctCheck(bulkRecord);
						if (dateCheck.getType().equals("Success")) {
							error = validate.validateAttachment(bulkRecord);
							if (error.getType().equals("Success") && bulkRecord.getDocType().matches("WO|TR")) {
								attachment = validate.getAttachment(bulkRecord.getId()).get(0);
								attachPath = sourcepath + File.separator + attachment.getPath() + File.separator
										+ attachment.getName();
								error = csvutils.markAssets(Paths.get(attachPath).normalize(), bulkRecord, false);
							}
							if (error.getType().equals("Success")) {
								bulkRecord.setDocStatus("APD");
								bulkRecord.setDocAction("NA");
								OBDal.getInstance().save(bulkRecord);
								JSONObject refreshCurrentRecord = new JSONObject();
								JSONObject refreshGrid = new JSONObject();
								refreshCurrentRecord.put("refreshCurrentRecord", new JSONObject());
								actions.put(refreshCurrentRecord);
								refreshGrid.put("refreshGrid", new JSONObject());
								actions.put(refreshGrid);
								respMsg.put("msgType", "success");
								respMsg.put("msgTitle", docNo + " - Approved Successfully.");
							} else {
								respMsg.put("msgType", "error");
								respMsg.put("msgTitle", error.getTitle());
								respMsg.put("msgText", error.getMessage());
							}
						} else {
							respMsg.put("msgType", dateCheck.getType());
							respMsg.put("msgTitle", dateCheck.getTitle() + "Reject the Request.");
							respMsg.put("msgText", dateCheck.getMessage());
						}
					} else {
						respMsg.put("msgType", "error");
						respMsg.put("msgTitle", "Invalid Operation.");
						respMsg.put("msgText", "Request already processed.");
					}
				} else {
					respMsg.put("msgType", "error");
					respMsg.put("msgTitle", "Request not found.");
					respMsg.put("msgText", "Please create new request.");
				}
				JSONObject msgTotalAction = new JSONObject();
				msgTotalAction.put("showMsgInProcessView", respMsg);
				action.put(msgTotalAction);
				result.put("responseActions", action);
			}
			return result;
		} catch (JSONException | OBException | CSVFileReaderExceptions e) {
			e.printStackTrace();
			return new JSONObject();
		} finally {
			OBContext.restorePreviousMode();
		}
	}

	private void setValidated(Attachment attachment) {
		Query query = OBDal.getInstance().getSession()
				.createSQLQuery("update C_FILE set SEQNO = '108' where C_FILE_ID = '" + attachment.getId() + "'");
		int i = query.executeUpdate();
		log.info(i + " Attachment marked");
	}
}