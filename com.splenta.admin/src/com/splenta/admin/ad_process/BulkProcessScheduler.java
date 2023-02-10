package com.splenta.admin.ad_process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.CallableStatement;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

import org.apache.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.financialmgmt.assetmgmt.Asset;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.service.db.DalBaseProcess;

import com.chimera.fixedassetmanagement.AmAssetTransfer;

import com.splenta.admin.BulkProcess;
import com.splenta.admin.ad_process.bulkprocesses.AssetValidations;
import com.splenta.admin.ad_process.bulkprocesses.ProcessAssetsUtils;
import com.splenta.admin.ad_process.bulkprocesses.TransferUtility;
import com.splenta.admin.ad_process.bulkprocesses.TransferVO;

public class BulkProcessScheduler extends DalBaseProcess {

	private static final Logger log = Logger.getLogger(BulkProcessScheduler.class);
	private static final String sourcepath = OBPropertiesProvider.getInstance().getOpenbravoProperties()
			.getProperty("attach.path");
	AssetValidations validate = WeldUtils.getInstanceFromStaticBeanManager(AssetValidations.class);
	ProcessAssetsUtils process = WeldUtils.getInstanceFromStaticBeanManager(ProcessAssetsUtils.class);
	private String failmsg = "", docType = "";
	private ProcessBundle sBundle;
	@Override
	public void doExecute(ProcessBundle bundle) throws Exception {
		try {
			sBundle = bundle;
			OBContext.setAdminMode();
			log.info("Starting . . . ");
			OBCriteria<BulkProcess> bpReq = OBDal.getInstance().createCriteria(BulkProcess.class);
			bpReq.add(Restrictions.eq(BulkProcess.PROPERTY_DOCSTATUS, "APD"));
			bpReq.add(Restrictions.eq(BulkProcess.PROPERTY_PROCESSED, false));
			log.info("Found Unprocessed Request: " + bpReq.list().get(0).getDocNumber());
			log.info("Is Today Reqeust ? "
					+ (bpReq.list().get(0).getAcctDate().compareTo(java.sql.Date.valueOf(LocalDate.now())) == 0 ? "YES"
							: "NO"));
			bpReq.add(Restrictions.eq(BulkProcess.PROPERTY_ACCTDATE, java.sql.Date.valueOf(LocalDate.now())));
			BulkProcess blkReq = bpReq.list().get(0);
			final String hql = "select e.name from ADList e where e.reference.id='9D2337FB3C984E8FB3201E04A7802979' and e.searchKey='"
					+ blkReq.getDocType() + "'";
			docType = OBDal.getInstance().getSession().createQuery(hql).uniqueResult().toString();
			setLog(blkReq.getDocNumber(), docType, blkReq.getAcctDate(), blkReq.getSrcOrg().getName());
			String attachPath = null;
			Attachment attachment;
			Path FILENAME = null;
			final Boolean isFile = blkReq.getDocType().matches("WO|TR");
			if (isFile) {
				try {
					OBError error = new OBError();
					error = validate.validateAttachment(blkReq);
					if (error.getType().equals("Success")) {
						attachment = validate.getAttachment(blkReq.getId()).get(0);
						attachPath = sourcepath + File.separator + attachment.getPath() + File.separator
								+ attachment.getName();
						FILENAME = Paths.get(attachPath).normalize();
						log.info("FILE >>> " + FILENAME);
					} else {
						setLog(error.getTitle());
						blkReq.setDocAction("AP");
						blkReq.setDocStatus("FWD");
						blkReq.setDescription(error.getTitle() + "\n" + error.getMessage());
						OBDal.getInstance().save(blkReq);
					}
				} catch (Exception e) {
					log.info("Attachment Exception: " + e);
					setLog("Attachment not found.");
					blkReq.setDocAction("AP");
					blkReq.setDocStatus("FWD");
					blkReq.setDescription("PROCESSING FAILED.\nAttachment not found.\nPlease Reject the request.");
					OBDal.getInstance().save(blkReq);
				}
			}
			processAssets(FILENAME, blkReq, bundle);
			OBDal.getInstance().getSession().flush();
			OBDal.getInstance().getSession().clear();
		} catch (Exception e) {
			log.info("Request Exception: " + e);
			setLog("Oops ! No valid pending request found.");
		}
		finally {
			bundle.getLogger().logln("End of process ");
			OBContext.restorePreviousMode();
		}
		log.info(". . . The End.");
	}

	/**
	 * @param FILENAME
	 *            Attached file
	 * @param bulkRecord
	 *            Unprocessed request
	 * @param bundle
	 *            To store the response
	 * @throws SQLException
	 */

	private void processAssets(Path FILENAME, BulkProcess bulkRecord, ProcessBundle bundle) {
		failmsg = docType + " Failed.Contact the CAMPS Admin.";
		if (bulkRecord.getDocType().matches("WO|TR")) {
			String line = null;
			HashSet<String> lines = new HashSet<>();
			List<Asset> assetList = new ArrayList<>();
			try (BufferedReader br = new BufferedReader(new FileReader(FILENAME.toString()))) {
				br.readLine();
				while ((line = br.readLine()) != null) {
					{
						if (lines.add(line)) {
							assetList.add(validate.getAsset(line));
						}
					}
				}
				br.close();
			} catch (IOException e) {
				log.warn("Error in Duplicatation Code" + e);
			}
			if (bulkRecord.getDocType().equals("TR")) {
				log.info("Transferring " + assetList.size() + " Assets . . .");
				setLog(bulkRecord.getDstnOrg().getName(), assetList.size());
				try {
					TransferVO transfervo = new TransferVO();
					transfervo.setSource_organization(bulkRecord.getSrcOrg());
					transfervo.setDestination_organization(bulkRecord.getDstnOrg());
					transfervo.setAssets(assetList);
					OBError error = new TransferUtility().transferAsset(transfervo);
					if (error.getType().equals("Success")) {
						String requestid = error.getMessage();
						AmAssetTransfer transferObj = OBDal.getInstance().get(AmAssetTransfer.class, requestid);
						OBError error1 = new TransferUtility().processTransfer(transferObj);
						if (error1.getType().equals("Success")) {
							log.info(error1.getType());
							log.info(error1.getMessage());
							bulkRecord.setProcessed(true);
							bulkRecord.setDescription(null);
							log.info("Transfered from " + bulkRecord.getSrcOrg().getName() + " to "
									+ bulkRecord.getDstnOrg().getName());
							setLog("Transfer Successfull.");
						} else {
							bulkRecord.setDescription(failmsg);
							setLog("Transfer Failed. --> " + error.getTitle() + error.getMessage());
						}
					} else {
						bulkRecord.setDescription(failmsg);
						setLog("Transfer Failed. --> " + error.getTitle() + error.getMessage());
					}
				} catch (Exception e) {
					bulkRecord.setDescription(failmsg);
					setLog("Transfer Failed. --> " + e);
					log.info(e);
					e.printStackTrace();
				} finally {
					OBDal.getInstance().save(bulkRecord);
				}
			} else {
				log.info("Writing Off the Assets . . .");
				OBCriteria<Asset> staticAstList = OBDal.getInstance().createCriteria(Asset.class);
				staticAstList.add(Restrictions.eq(Asset.PROPERTY_STATIC, true));
				final int astCount = staticAstList.list().size();
				log.info("Uploaded Assets: " + assetList.size() + " | Marked Assets: " + staticAstList.list().size());
				setLog("NA", astCount);
				if (assetList.size() == astCount) {
					try {
						log.info("Writing Off " + staticAstList.list().size() + " Assets.");
						CallableStatement callProc = OBDal.getInstance().getConnection()
								.prepareCall("{call AP_WRITEOFF_PROCESS(?)}");
						callProc.setString(1, bulkRecord.getId());
						callProc.execute();
						log.info("Created WriteOff Request(s).");
						setLog("Created WriteOff Request(s).");
						OBError status = process.processWriteOffRequest(bulkRecord, bundle);
						if (status.getType().equals("Success")) {
							bulkRecord.setProcessed(true);
							bulkRecord.setDescription(null);
							setLog("WriteOff Successfull.");
						} else {
							bulkRecord.setDescription(failmsg);
							setLog("WriteOff Failed.");
						}
					} catch (SQLException e) {
						e.printStackTrace();
						bulkRecord.setDescription(failmsg);
						setLog("WriteOff Failed. --> " + e);
					} finally {
						OBDal.getInstance().save(bulkRecord);
					}
				} else {
					setLog("WriteOff Failed. Uploaded Count: " + assetList.size() + " | Static Count: " + astCount);
					bulkRecord.setDescription(failmsg);
					OBDal.getInstance().save(bulkRecord);
				}
			}
		} else {
			OBCriteria<Asset> assetList = OBDal.getInstance().createCriteria(Asset.class);
			assetList.add(Restrictions.eq(Asset.PROPERTY_ORGANIZATION, bulkRecord.getSrcOrg()));
			assetList.add(Restrictions.eq(Asset.PROPERTY_DISPOSED, false));
			final String txnDate = new SimpleDateFormat("dd-MM-YYYY").format(bulkRecord.getAcctDate());
			setLog(bulkRecord.getDstnOrg().getName(), assetList.list().size());
			try {
				log.info("Tranferring . . .");
				CallableStatement process = OBDal.getInstance().getConnection()
						.prepareCall("{call AP_CLONE_ORG_ASSETS(?,?,?)}");
				log.info("\n Source: " + bulkRecord.getSrcOrg().getId() + "\n Destination: "
						+ bulkRecord.getDstnOrg().getId() + "\n Date: " + txnDate);
				process.setString(1, bulkRecord.getSrcOrg().getId());
				process.setString(2, bulkRecord.getDstnOrg().getId());
				process.setString(3, txnDate);
				process.execute();
				bulkRecord.setProcessed(true);
				bulkRecord.setDescription(null);
				setLog("Transfer Successfull.");
			} catch (SQLException e) {
				bulkRecord.setDescription(failmsg);
				setLog("Transfer Failed. --> " + e);
				log.info("Transfer Failed. --> " + e);
			} finally {
				OBDal.getInstance().save(bulkRecord);
			}
		}
		OBDal.getInstance().flush();
	}

	private void setLog(String docNo, String docType, Date date, String srcOrg) {
		sBundle.getLogger().logln("\n Document Number: " + docNo + "\n Document Type: " + docType
				+ "\n Accounting  Date: " + date + "\n Source Branch: " + srcOrg);
	}

	private void setLog(String desOrg, int assets) {
		sBundle.getLogger().logln("\n Destination Branch: " + desOrg + "\n Number of Assets : " + assets);
	}

	private void setLog(String message) {
		sBundle.getLogger().logln(message);
	}
}