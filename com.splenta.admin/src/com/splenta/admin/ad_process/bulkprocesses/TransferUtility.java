package com.splenta.admin.ad_process.bulkprocesses;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections.map.HashedMap;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.log4j.Logger;
import org.hibernate.Query;
import org.openbravo.advpaymentmngt.process.FIN_AddPaymentFromJournal;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.process.ProcessInstance;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.financialmgmt.accounting.coa.ElementValue;
import org.openbravo.model.financialmgmt.assetmgmt.AmortizationLine;
import org.openbravo.model.financialmgmt.assetmgmt.Asset;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.gl.GLJournal;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.service.db.CallProcess;

import com.chimera.finaclewebservice.ad_process.FinaclePostings;
import com.chimera.fixedassetmanagement.AmAssetTransfer;
import com.chimera.fixedassetmanagement.AmAssetTransferSubLN;
import com.chimera.fixedassetmanagement.AmAssetTransferline;
import com.chimera.fixedassetmanagement.AssetType;
import com.chimera.fixedassetmanagement.ad_process.AssetTransfer;
import com.chimera.fixedassetmanagement.ad_process.Assetization;
import com.splenta.pmn.ad_process.finacleFiles.GLJournalVO;
import com.splenta.pns.ad_process.GSTPostingsToFinacle;

public class TransferUtility {

	private static final Logger log4j = Logger.getLogger(TransferUtility.class);
	private final static String TR_DOCUMENT_ID = "E922BA4620A1446EAB6878F8C438138E";
	FinaclePostings posutil = new FinaclePostings();

	/**
	 * The following code will create transfer object for the specified source
	 * and destination organization - asset type wise
	 * 
	 * @author Vikas
	 * @param transfervo
	 * @return
	 */
	@SuppressWarnings("rawtypes")
	public OBError transferAsset(TransferVO transfervo) {
		OBError error = new OBError();
		try {
			String desc = "Asset transfer from " + transfervo.getSource_organization().getName() + " to "
					+ transfervo.getDestination_organization().getName() + ",intiated by "
					+ OBContext.getOBContext().getUser().getName();

			AmAssetTransfer assettransferobj = new AmAssetTransfer();
			assettransferobj.setOrganization(transfervo.getDestination_organization());
			assettransferobj.setSourceBranch(transfervo.getSource_organization());
			assettransferobj.setDestinationBranch(transfervo.getDestination_organization());
			assettransferobj.setRequestor(OBContext.getOBContext().getUser());
			assettransferobj.setDescription(desc);
			assettransferobj.setAssetTransferStatus("TR_ACPT");
			assettransferobj.setDocumentStatus("TR_CL");
			assettransferobj.setTransferdate(new Date());
			OBDal.getInstance().save(assettransferobj);

			HashMap<String, String> product = new HashMap<>();
			HashMap<String, Integer> line = new HashMap<>();
			HashMap<String, String> transline = new HashMap<>();
			for (Asset asset : transfervo.getAssets()) {
				if (line.containsKey(asset.getProduct().getAmAssetType().getId())) {
					Integer count = line.get(asset.getProduct().getAmAssetType().getId());
					line.put(asset.getProduct().getAmAssetType().getId(), count + 1);
				} else {
					line.put(asset.getProduct().getAmAssetType().getId(), 1);
					product.put(asset.getProduct().getAmAssetType().getId(), asset.getProduct().getId());
				}
			}
			Iterator it = line.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry pair = (Map.Entry) it.next();
				System.out.println(pair.getKey() + " = " + pair.getValue());
				AmAssetTransferline trline = new AmAssetTransferline();
				trline.setOrganization(transfervo.getDestination_organization());
				trline.setAssetTransfer(assettransferobj);
				trline.setDescription(desc);
				trline.setAssetType(OBDal.getInstance().getProxy(AssetType.class, "" + pair.getKey()));
				trline.setProduct(OBDal.getInstance().getProxy(Product.class, product.get(pair.getKey())));
				trline.setQuantity(Long.parseLong("" + pair.getValue()));
				trline.setTransferQuantity(Long.parseLong("" + pair.getValue()));
				OBDal.getInstance().save(trline);
				transline.put("" + pair.getKey(), trline.getId());
				it.remove(); // avoids a ConcurrentModificationException
			}

			Iterator translineit = transline.entrySet().iterator();
			while (translineit.hasNext()) {
				Map.Entry pair = (Map.Entry) translineit.next();
				System.out.println(pair.getKey() + " = " + pair.getValue());
				for (Asset asset : transfervo.getAssets()) {
					if (asset.getProduct().getAmAssetType().getId().equals(pair.getKey())) {
						AmAssetTransferSubLN subline = new AmAssetTransferSubLN();
						subline.setOrganization(transfervo.getDestination_organization());
						subline.setAsset(asset);
						subline.setLineTransferStatus("TR_ACPT");
						subline.setInternalNotes(desc);
						subline.setAssetTransferline(
								OBDal.getInstance().get(AmAssetTransferline.class, pair.getValue()));
						OBDal.getInstance().save(subline);
					}
				}
			}
			error.setType("Success");
			error.setTitle("Request Created Successfully");
			error.setMessage(assettransferobj.getId());
			OBDal.getInstance().flush();
		} catch (Exception e) {
			error.setType("ERROR");
			error.setTitle("Error Occured");
			error.setMessage(ExceptionUtils.getMessage(e));
		}
		OBDal.getInstance().commitAndClose();
		return error;

	}

	public OBError processTransfer(AmAssetTransfer transferObj) {
		// , TransferVO vo
		OBError message = new OBError();
		VariablesSecureApp vars = new VariablesSecureApp(transferObj.getCreatedBy().getId(),
				transferObj.getClient().getId(), transferObj.getOrganization().getId(),
				"AE1BC6D536CF4E6281DBDEF61E4409D0", "en_US");
		log4j.info("Starting the disposal process on " + transferObj.getDocumentNo());
		ProcessBundle bundle = new ProcessBundle("111", vars);
		com.chimera.fixedassetmanagement.vo.TransferVO transferVO = new com.chimera.fixedassetmanagement.vo.TransferVO();
		transferVO.setSourceOrganization(transferObj.getSourceBranch());
		transferVO.setDestnOrganization(transferObj.getDestinationBranch());
		AmAssetTransferline line = transferObj.getAMASSETTRANSFERLINEList().get(0);
		List<Asset> assetlist = new ArrayList<>();
		for (AmAssetTransferSubLN subline : line.getAMASSETTRANSFERSUBLINEList()) {
			assetlist.add(subline.getAsset());
		}

		transferVO.setIdleAssetList(assetlist);
		new AssetTransfer().customAssetDisposal(transferObj, bundle, transferVO);
		// OBDal.getInstance().flush();

		Assetization assetization = new Assetization();
		assetization.doAssetization(transferVO, bundle);
		
		/*-----Added by Satya-------*/
		BigDecimal resval_total = BigDecimal.ZERO;
		for (String assetid : transferVO.getAssetIdList()) {
			double resval = calResidualVal(assetid);
			resval_total = resval_total.add(BigDecimal.valueOf(resval));
		}
		log4j.info("amount which is going to post in :" + resval_total);
		/*---------------*/
		
		log4j.info("Asset Transfer before processJournalEntry");
		OBError error = processjournals(transferObj, vars, resval_total);
		if (error.getType().equals("Error")) {
			log4j.info("Error Message " + error.getMessage());
			message.setType("Error");
			message.setTitle("Error Occured while creating the journal entries");
		} else {

			message.setType("Success");
			message.setTitle("Asset Transfer Completed");
			message.setMessage("Transfer of asset from " + transferObj.getSourceBranch().getName() + " to "
					+ transferObj.getDestinationBranch().getName() + " is Accepted and completed");
		}

		return message;
	}

	private double calResidualVal(String assetid) {
		Asset asset = OBDal.getInstance().get(Asset.class, assetid);
		double amttemp = 0.0d;
		String HQL_QUERY = "select aml from org.openbravo.model.financialmgmt.assetmgmt.AmortizationLine aml,org.openbravo.model.financialmgmt.assetmgmt.Amortization am where aml.asset.id='"
				+ assetid + "' and aml.amortization.id = am.id  and aml.aMBookValue>0 ORDER BY am.accountingDate desc";
		@SuppressWarnings("unchecked")
		List<AmortizationLine> amlist = OBDal.getInstance().getSession().createQuery(HQL_QUERY).list();
		if (amlist.size() > 0) {
			log4j.info("Amortization Lines available.");
			amttemp = amttemp + (amlist.get(0).getAMBookValue().doubleValue()
					- amlist.get(0).getAmortizationAmount().doubleValue());
			asset.setAmBrokenDay(amlist.get(0).getAmortizationAmount());
			OBDal.getInstance().save(asset);
			return amttemp;
		} else {
			double residualval = 0.0d;
			String deptype = "";
			double depvalue = 0.0d;
			try {
				OBContext.setAdminMode();
				deptype = asset.getProduct().getAmAssetType().getAmdepreciationtype().toString();
				depvalue = asset.getProduct().getAmAssetType().getAmdepreciationtypevalue().doubleValue();
				if (deptype.equalsIgnoreCase("VAL")) {
					residualval = depvalue;
					amttemp = amttemp + residualval;
				} else {
					double addcost = 0.0;
					double credittaken = 0.0;
					double assetval = 0.0;
					if (asset.getIlastAdditionalCost() != null) {
						addcost = asset.getIlastAdditionalCost().doubleValue();
					}
					if (asset.getIlastCreditTaken() != null) {
						credittaken = asset.getIlastCreditTaken().doubleValue();
					}
					assetval = ((asset.getIlastPurchaseCost().doubleValue() + addcost) - credittaken);
					residualval = assetval * (depvalue / 100);
					log4j.info("final residualval:" + residualval);
					amttemp = amttemp + residualval;
				}
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				OBContext.restorePreviousMode();
			}
			return amttemp;
		}
	}

	public OBError processjournals(AmAssetTransfer transferObj, VariablesSecureApp vars, BigDecimal amount) {
		OBError message = new OBError();
		try {
			ElementValue elv = GSTPostingsToFinacle.getDefaultAccountSchema().getFixedAsset().getAccount();

			GLJournalVO interim = new GLJournalVO();
			interim.setRemiteracctname(elv.getName());
			interim.setBenfiacctname(elv.getName());
			interim.setDocumentno(transferObj.getDocumentNo());
			interim.setRemiteracctnowithoutsolid(elv.getFwAccountNo());
			interim.setBenfiacctnowithoutsolid(elv.getFwAccountNo());
			interim.setReferenceid("@" + transferObj.getDocumentNo());
			interim.setDescription("@" + transferObj.getDocumentNo());
			interim.setAmount(amount);
			interim.setOrg(transferObj.getSourceBranch());
			createJournals(interim, vars);
			if (interim.isStatus()) {
				log4j.info("Journal Documentno:" + interim.getDocumentno());
				message.setType("Success");
				message.setMessage("Created Successfully " + interim.getDocumentno());
			} else {
				message.setType("Error");
				message.setMessage("Error while creating GL Journal :" + interim.getResponsemsg());
			}
		} catch (Exception e) {
			e.printStackTrace();
			message.setType("Error");
			message.setMessage("Error " + ExceptionUtils.getMessage(e));
		}

		return message;
	}

	public static GLJournalVO createJournals(GLJournalVO glvo, VariablesSecureApp vars) {
		try {
			String clientId = OBContext.getOBContext().getCurrentClient().getId();
			Period currentPeriod = OBProvider.getInstance().get(Period.class);
			Calendar firstDateOfMonth = Calendar.getInstance();
			firstDateOfMonth.set(Calendar.DAY_OF_MONTH, 1);
			String HQL_C_PERIOD = "select p from org.openbravo.model.financialmgmt.calendar.Period p where p.startingDate = :Date"
					+ " and p.client.id = '" + clientId + "'";
			Query query = OBDal.getInstance().getSession().createQuery(HQL_C_PERIOD);
			query.setDate("Date", firstDateOfMonth.getTime());
			currentPeriod = (Period) query.uniqueResult();
			log4j.info("Current Period: " + currentPeriod.getName());
			String validcomb_sqldr = "select cv.c_validcombination_id from c_elementvalue ce inner join C_ValidCombination cv on cv.account_id=ce.c_elementvalue_id where ce.em_fw_account_no='"
					+ glvo.getRemiteracctnowithoutsolid() + "' and rownum<2";
			String accdr = (String) OBDal.getInstance().getSession().createSQLQuery(validcomb_sqldr).uniqueResult();
			String validcomb_sqlcr = "select cv.c_validcombination_id from c_elementvalue ce inner join C_ValidCombination cv on cv.account_id=ce.c_elementvalue_id where ce.em_fw_account_no='"
					+ glvo.getBenfiacctnowithoutsolid() + "' and rownum<2";
			String acccr = (String) OBDal.getInstance().getSession().createSQLQuery(validcomb_sqlcr).uniqueResult();

			String GET_PROCESS_ID_HQL = "select p.id from org.openbravo.model.ad.ui.Process p where value='CREATEGL'";
			String exegl = (String) OBDal.getInstance().getSession().createQuery(GET_PROCESS_ID_HQL).uniqueResult();
			String gljournalid = "";
			gljournalid = (String) OBDal.getInstance().getSession().createSQLQuery("select get_uuid() from dual")
					.uniqueResult();

			org.openbravo.model.ad.ui.Process process = OBDal.getInstance().get(org.openbravo.model.ad.ui.Process.class,
					exegl);

			@SuppressWarnings("unchecked")
			Map<String, String> parameters = new HashedMap();
			parameters.put("V_PERIOD_ID", currentPeriod.getId());
			parameters.put("V_ORG_ID", glvo.getOrg().getId());
			parameters.put("V_INVOICE_ID", glvo.getReferenceid());
			parameters.put("V_AMTCR", "" + glvo.getAmount());
			parameters.put("V_AMTDR", "" + glvo.getAmount());
			parameters.put("V_VALIDCOMBICR_ID", acccr);
			parameters.put("V_VALIDCOMBIDR_ID", accdr);
			parameters.put("V_GLJOURNAL_ID", gljournalid);
			parameters.put("V_DESC", glvo.getDescription());
			parameters.put("V_DOCTYPE_ID", TR_DOCUMENT_ID);

			ProcessInstance pi = CallProcess.getInstance().callProcess(process, glvo.getReferenceid(), parameters);
			log4j.info("Result: " + pi.getResult());
			log4j.info("Error message: " + pi.getErrorMsg());

			OBDal.getInstance().flush();
			OBDal.getInstance().refresh(pi);

			StringBuilder builder = new StringBuilder();
			if (pi.getResult() != null) {
				if (pi.getResult() == 1) {
					builder.append("Result :" + pi.getResult());
					builder.append("ERROR:" + pi.getErrorMsg());
					GLJournal journal = OBDal.getInstance().get(GLJournal.class, gljournalid);
					if (journal != null) {
						log4j.info("GL IS AVAILABLE " + journal.getDocumentType().getName());
						ProcessBundle pb = new ProcessBundle("5BE14AA10165490A9ADEFB7532F7FA94", vars);
						HashMap<String, Object> param = new HashMap<String, Object>();
						param.put("GL_Journal_ID", journal.getId());
						pb.setParams(param);
						try {
							FIN_AddPaymentFromJournal finProcess = new FIN_AddPaymentFromJournal();
							finProcess.execute(pb);
						} catch (Exception e) {
							e.printStackTrace();
						}

						glvo.setDescription(journal.getId());
						glvo.setResponsemsg(
								"Records moved to journal entrys with amount " + glvo.getAmount().setScale(2, 4));
						glvo.setStatus(true);

					} else {
						log4j.info("GL IS NOT AVAILABLE");
						glvo.setResponsemsg("GL IS NOT AVAILABLE");
						glvo.setStatus(false);
					}
				}
			} else {
				builder.append("Result :" + pi.getResult());
				glvo.setResponsemsg(builder.toString());
				glvo.setStatus(false);
			}
		} catch (Exception e) {
			e.printStackTrace();
			glvo.setResponsemsg(e.getMessage());
		}
		return glvo;
	}
}