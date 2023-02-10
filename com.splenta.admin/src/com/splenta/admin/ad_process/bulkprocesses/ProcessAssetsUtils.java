package com.splenta.admin.ad_process.bulkprocesses;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections.CollectionUtils;
import org.apache.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.financialmgmt.assetmgmt.AmortizationLine;
import org.openbravo.model.financialmgmt.assetmgmt.Asset;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.model.ad.process.ProcessInstance;
import com.chimera.fixedassetmanagement.AmAssetDisposal;
import com.chimera.fixedassetmanagement.AmAssetDisposalLine;
import com.chimera.fixedassetmanagement.AmAssetDisposalLnStatus;
import com.chimera.fixedassetmanagement.ad_process.CalculateAmortizationFB;
import com.chimera.fixedassetmanagement.gstsaleutil.GLJournalUtil;
import com.chimera.fixedassetmanagement.gstsaleutil.GstSaleUtility;
import com.splenta.admin.BulkProcess;

import org.openbravo.service.db.CallProcess;
import org.openbravo.model.ad.ui.Process;


public class ProcessAssetsUtils {
	private static final Logger log = Logger.getLogger(ProcessAssetsUtils.class);

	public OBError processWriteOffRequest(BulkProcess bulkRecord, ProcessBundle bundle) {
		OBError err = new OBError();
		String dispReqNo = null;
		try {
			OBCriteria<AmAssetDisposal> woList = OBDal.getInstance().createCriteria(AmAssetDisposal.class);
			woList.add(Restrictions.eq(AmAssetDisposal.PROPERTY_APBULKPROCESS, bulkRecord));
			woList.add(Restrictions.eq(AmAssetDisposal.PROPERTY_WRITEOFF, "writeoff"));
			woList.add(Restrictions.eq(AmAssetDisposal.PROPERTY_DOCUMENTSTATUS, "ABR_CO"));
			for (AmAssetDisposal dispReq : woList.list()) {
				dispReqNo = dispReq.getDocumentNo();
				bundle.getLogger().logln("WriteOff RequestID: " + dispReqNo);
				log.info("Processing > " + dispReqNo);
				err = completeWriteOff(dispReq, bundle);
				if (err.getType().equals("Success")) {
					try {
					//	if (!dispReq.getAMASSETDISPOSALLINEList().isEmpty()) {
							
							Process process = OBDal.getInstance().get(Process.class, "B73F3E8B44D14E17B3DF1FF31A569412");
							Map<String, String> parameters = null;
							final ProcessInstance pinstance = CallProcess.getInstance().call(process, dispReq.getId(),
									parameters);
							if (pinstance.getResult() == 1) {

								log.info(pinstance.getErrorMsg());
							} else {
								log.info(pinstance.getErrorMsg());
							}
							
							
							
//							for (AmAssetDisposalLine line : dispReq.getAMASSETDISPOSALLINEList()) {
//								if (!line.getLineStatus().equalsIgnoreCase("DIS_AP")) {
//									String Asset_HQL = "select asset from org.openbravo.model.financialmgmt.assetmgmt.Asset "
//											+ "asset where asset.id='" + line.getAsset().getId() + "'";
//									Asset asset = (Asset) OBDal.getInstance().getSession().createQuery(Asset_HQL)
//											.uniqueResult();
//									asset.setAmIsdisprocessing(false);
//									OBDal.getInstance().getSession().saveOrUpdate(asset);
//								}
//							}
						//}
					} catch (Exception exception) {
						exception.printStackTrace();
					}
					saveOrUpdateDisposalReqLineHistory(dispReq);
					bundle.getLogger().logln("Completed the Asset Disposal Requisition");
				} else {
					bundle.getLogger().logln("\n" + dispReqNo + " - " + err.getTitle());
				}
			}
		} catch (Exception e) {
			err.setType("Error");
			bundle.getLogger().logln("\n" + dispReqNo + " - WriteOff Failed." + e);
			log.info(e);
			e.printStackTrace();
		}
		return err;
	}

	private OBError completeWriteOff(AmAssetDisposal disposalReq, ProcessBundle sBundle) {
		OBError message = new OBError();
		try {
			int error = 0;
			Asset asset = null;
			
			final OBQuery<AmAssetDisposalLine> dispLine = OBDal.getInstance().createQuery(AmAssetDisposalLine.class,
					"assetDisposal.id='" + disposalReq.getId() + "'");
			
			final List<AmAssetDisposalLine> amdispLne = dispLine.list();
			
			for (AmAssetDisposalLine disposalLine : amdispLne) {
			
		//	for (AmAssetDisposalLine disposalLine : disposalReq.getAMASSETDISPOSALLINEList()) {
				if (disposalLine.getStatus().equals("H_AM_AP")) {
					asset = disposalLine.getAsset();
					log.info("Disposing - " + asset.getSearchKey());
					VariablesSecureApp vars = new VariablesSecureApp(disposalLine.getUpdatedBy().getId(),
							disposalLine.getClient().getId(), disposalLine.getOrganization().getId(),
							disposalLine.getUpdatedBy().getId(), "en_US");
					// TODO How it Worked?
					ProcessBundle bundle = new ProcessBundle("583BF9EB9823473A90E6D8965CF01DD8", vars);
					boolean isDisposed = disposeAsset(asset, bundle);
					if (!isDisposed) {
						sBundle.getLogger().logln(asset.getSearchKey() + " - Diposal Failed.");
						error++;
					} else {
						sBundle.getLogger().logln("Disposed - " + disposalLine.getAsset().getSearchKey());
					}

				} else {
					log.info("Line status is not approved");
				}
			}
			if (error <= 0) {
				String UPDATE_STATUS_LINE = "update AM_ASSET_DISPOSAL set STATUS='H_DIS_CO' where AM_ASSET_DISPOSAL_ID='"
						+ disposalReq.getId() + "'";
				OBDal.getInstance().getSession().createSQLQuery(UPDATE_STATUS_LINE).executeUpdate();
				VariablesSecureApp vars = new VariablesSecureApp(disposalReq.getUpdatedBy().getId(),
						disposalReq.getClient().getId(), disposalReq.getOrganization().getId(),
						disposalReq.getRole().getId());
				try {
					OBContext.setAdminMode();
					GstSaleUtility gstSaleUtility = new GstSaleUtility();
					GLJournalUtil glJournalUtil = new GLJournalUtil();
					List<com.chimera.fixedassetmanagement.gstsaleutil.GstSaleUtility.SaleVO> saleList = gstSaleUtility
							.getGLReversalDetailsWriteoff(disposalReq);
					if (CollectionUtils.isNotEmpty(saleList)) {
						glJournalUtil.createGLJournalEntries(saleList, null, disposalReq);
					}
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					OBContext.restorePreviousMode();
				}
				DisposalAccounting.pushEntrysToFactAcct(disposalReq.getId(), vars);
				sBundle.getLogger().logln("Pushed to FactAcct Succesfully.");
				message.setType("Success");
			} else {
				message.setType("Error");
				message.setTitle(error + " Error(s) Occured while Disposing.");
				sBundle.getLogger().logln("Asset(s) Disposal Failed.");
			}
		} catch (Exception e) {
			message.setType("Error");
			message.setTitle("Disposal Failed." + e);
			sBundle.getLogger().logln("Error while Disposing" + e);
			log.info(e);
			e.printStackTrace();
		}
		return message;
	}

	@SuppressWarnings("unchecked")
	private void saveOrUpdateDisposalReqLineHistory(AmAssetDisposal disposalReq) {
		String SELECT_RC_LINE_HQL = "";
		try {
			/* Adding the Line History */
			SELECT_RC_LINE_HQL = "select p from com.chimera.fixedassetmanagement.AmAssetDisposalLine p "
					+ "where p.assetDisposal.id ='" + disposalReq.getId() + "'";
			List<AmAssetDisposalLine> reqLineList = OBDal.getInstance().getSession().createQuery(SELECT_RC_LINE_HQL)
					.list();
			if (reqLineList != null) {
				for (AmAssetDisposalLine line : reqLineList) {
					if (line.getUpdatedBy().getId().equalsIgnoreCase(OBContext.getOBContext().getUser().getId())) {
						log.info("Insering the AP. RE Line status");
						AmAssetDisposalLnStatus lineStatus = OBProvider.getInstance()
								.get(AmAssetDisposalLnStatus.class);
						lineStatus.setClient(line.getClient());
						lineStatus.setOrganization(line.getOrganization()); //
						lineStatus.setDocumentNo(disposalReq.getDocumentNo());
						lineStatus.setProduct(line.getProduct());
						lineStatus.setAsset(line.getAsset());
						lineStatus.setAssetType(line.getAssetType());
						lineStatus.setInternalNotes(line.getInternalNotes());
						lineStatus.setAssetDisposal(disposalReq);
						lineStatus.setAssetDisposalLine(line);
						log.info("Line status =" + line.getLineStatus());
						lineStatus.setDisposalLineStatus(line.getLineStatus());
						lineStatus.setRole(OBContext.getOBContext().getRole());
						OBDal.getInstance().save(lineStatus);

					} else {
						log.info("Insering the Line status");
						AmAssetDisposalLnStatus lineStatus = OBProvider.getInstance()
								.get(AmAssetDisposalLnStatus.class);
						lineStatus.setClient(line.getClient());
						lineStatus.setOrganization(line.getOrganization()); //
						lineStatus.setDocumentNo(disposalReq.getDocumentNo());
						lineStatus.setProduct(line.getProduct());
						lineStatus.setAsset(line.getAsset());
						lineStatus.setAssetType(line.getAssetType());
						lineStatus.setInternalNotes(line.getInternalNotes());
						lineStatus.setAssetDisposal(disposalReq);
						lineStatus.setAssetDisposalLine(line);
						lineStatus.setHistory("Approved By : " + disposalReq.getRole().getName());
						log.info("Line status =" + line.getLineStatus());
						if (line.getLineStatus().equalsIgnoreCase("DIS_RE")) {
							log.info("Asset " + line.getAsset().getName() + " rejected");
							lineStatus.setHistory("Rejected By : " + disposalReq.getRole().getName());
							Asset assetReject = OBDal.getInstance().get(Asset.class, line.getAsset().getId());
							assetReject.setDisposed(false);
							OBDal.getInstance().save(assetReject);
							OBDal.getInstance().flush();
							log.info("disposed Status " + assetReject.isDisposed());
						}
						lineStatus.setDisposalLineStatus(line.getLineStatus());
						lineStatus.setRole(OBContext.getOBContext().getRole());
						OBDal.getInstance().save(lineStatus);
					}
				}
			}
		} catch (Exception exception) {
			exception.printStackTrace();
		}
	}

	public boolean disposeAsset(Asset asset, ProcessBundle bundle) {
		boolean isDispose = false;
		try {
			Map<String, Object> paramsMap = null;
			try {
				OBContext.setAdminMode(false);
				String AMORTIZATION_LINE_NOT_PROCESS_HQL = "select aml from org.openbravo.model.financialmgmt.assetmgmt.AmortizationLine aml,"
						+ "org.openbravo.model.financialmgmt.assetmgmt.Amortization am where aml.asset.id='"
						+ asset.getId()
						+ "' and aml.amortization.id = am.id and am.processed = 'N' ORDER BY am.startingDate ASC";
				@SuppressWarnings("unchecked")
				List<AmortizationLine> amortizationLineList = OBDal.getInstance().getSession()
						.createQuery(AMORTIZATION_LINE_NOT_PROCESS_HQL).list();
				if (amortizationLineList != null && amortizationLineList.size() > 0) {
					log.info("Unprocessed AmortizationLines : " + amortizationLineList.size());
					int deleteValue = 0;
					for (AmortizationLine amortizationLine : amortizationLineList) {
						AmortizationLine amortizationLineObj = amortizationLine;
						String amortizationLineId = amortizationLineObj.getId();
						String AMORTIZATIONLINE_DELETE_HQL = "delete from org.openbravo.model.financialmgmt.assetmgmt.AmortizationLine aml where aml.id ='"
								+ amortizationLineId + "'";
						deleteValue = deleteValue + OBDal.getInstance().getSession()
								.createQuery(AMORTIZATIONLINE_DELETE_HQL).executeUpdate();
					}
					log.info("Deleted " + deleteValue + " amortization lines.");
				}
				paramsMap = new HashMap<String, Object>();
				paramsMap.put("A_Asset_ID", asset.getId());
				paramsMap.put("A_DISPOSAL_FLAF", "Y");
				bundle.setParams(paramsMap);
				new CalculateAmortizationFB().doExecute(bundle);
				OBDal.getInstance().flush();
			} catch (Exception exception) {
				exception.printStackTrace();
				throw new OBException(exception);
			} finally {
				OBContext.restorePreviousMode();
			}
			isDispose = true;
		} catch (Exception exception) {
			exception.printStackTrace();
		}
		return isDispose;
	}
}