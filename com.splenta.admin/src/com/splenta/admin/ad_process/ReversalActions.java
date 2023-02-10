package com.splenta.admin.ad_process;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.client.kernel.BaseActionHandler;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.ui.ProcessRequest;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.financialmgmt.assetmgmt.Asset;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

import com.chimera.fixedassetmanagement.ad_process.ErrorMessage;
import com.splenta.admin.ad_process.bulkprocesses.AssetValidations;
import com.splenta.admin.ad_process.reversals.FIN_PaymentProcess;
import com.splenta.admin.ad_process.reversals.GSTUtility;
import com.splenta.admin.ad_process.reversals.ReversalUtility;
import com.splenta.admin.ad_process.reversals.GSTUtility.Account;
import com.splenta.admin.ad_process.reversals.InvoiceReversal;
import com.splenta.gst.GSTPOSTINGS;

public class ReversalActions extends BaseActionHandler {
	private static Logger log = Logger.getLogger(ReversalActions.class);
	AssetValidations validate = WeldUtils.getInstanceFromStaticBeanManager(AssetValidations.class);
	ReversalUtility revUtil = WeldUtils.getInstanceFromStaticBeanManager(ReversalUtility.class);
	GSTPOSTINGS gstPost = WeldUtils.getInstanceFromStaticBeanManager(GSTPOSTINGS.class);
	InvoiceReversal reversal = WeldUtils.getInstanceFromStaticBeanManager(InvoiceReversal.class);

	final LocalDate qtrStart = validate.getQuarterBeginDate(LocalDate.now());

	@Override
	protected JSONObject execute(Map<String, Object> parameters, String content) {
		try {
			JSONArray action = new JSONArray();
			JSONObject result = new JSONObject();
			JSONObject respMsg = new JSONObject();
			JSONObject request = new JSONObject(content);
			JSONObject params = request.getJSONObject("_params");
			OBError status = new OBError();
			if (request.getString("_buttonValue").equals("DONE")) {
				try {
					log.warn("content:" + content);
					String revType = params.getString("revType");
					String m_inout_id = params.getString("m_inout_id");
					String c_invoice_id = params.getString("c_invoice_id");
					String fin_payment_id = params.getString("fin_payment_id");
					JSONArray gstPosts = params.getJSONArray("gst_postings_id");
					switch (revType) {
					case "GR":
						status = reverseGR(m_inout_id);
						break;
					case "INV":
						status = reverseInv(c_invoice_id);
						break;
					case "GST":
						status = reverseGST(gstPosts);
						break;
					case "PR":
						status = reversePay(fin_payment_id);
						break;
					default:
						log.info("Selected nothing.");
						status.setType("error");
						status.setTitle("Insufficient Data.");
						status.setMessage("Please fill requested fields.");
						break;
					}
				} catch (Exception e) {
					e.printStackTrace();
					status.setType("error");
					status.setTitle("Insufficient Data.");
					status.setMessage("ReOpen & Fill requested fields to Process.");
				}
				respMsg.put("msgType", status.getType());
				respMsg.put("msgTitle", status.getTitle());
				respMsg.put("msgText", status.getMessage());
				JSONObject msgTotalAction = new JSONObject();
				msgTotalAction.put("showMsgInProcessView", respMsg);
				action.put(msgTotalAction);
				result.put("responseActions", action);
			}
			return result;
		} catch (JSONException e) {
			e.printStackTrace();
			return new JSONObject();
		} finally {
			log.info("Adapuli!!!");
		}
	}

	private OBError reversePay(String payId) {
		OBError error = new OBError();
		FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, payId);
		reversePayment(payment);
		error.setType("success");
		error.setTitle("Payment Reversal Successful." + payment.getDocumentNo());
		error.setMessage("Please handle the Postings.");
		return error;
	}

	private OBError reverseGST(JSONArray gstPosts) {
		OBError error = new OBError();
		StringBuilder txnRespS = new StringBuilder();
		StringBuilder txnRespF = new StringBuilder();
		try {
			OBContext.setAdminMode();
			int count = 1;
			for (int g = 0; g < gstPosts.length(); g++) {
				GSTPOSTINGS gstPost = OBDal.getInstance().get(GSTPOSTINGS.class, gstPosts.get(g));
				log.info("Reversing - " + gstPost.getGSTType());
				Account acct = new GSTUtility().getaccountNumber(gstPost);
				ErrorMessage msg = ReversalUtility.FinaclePosting(gstPost.getOrganization().getId(),
						gstPost.getAmount().floatValue(), acct.getCreditacct(), acct.getDebitacct(), "318",
						gstPost.getInvoice().getId(), acct.getRemsol(), acct.getBensol(), "REV",
						gstPost.getDocumentno());
				String resp = count + ".[" + gstPost.getGSTType() + " > " + msg.getMessage() + "]";
				if (msg.isStatus()) {
					gstPost.setType("REVERSAL");
					gstPost.setUpdated(new Date());
					// TODO Remove after testing.
					gstPost.setGSTType(gstPost.getGSTType() + "_REV");
					OBDal.getInstance().save(gstPost);
					OBDal.getInstance().flush();
					txnRespS.append(resp + " > SUCCESS");
				} else {
					txnRespF.append(resp + " > FAIL");
				}
				count++;
			}
			error.setType((txnRespF.length() == 0) ? "success" : "error");
			error.setTitle("GST Reversal Processed. Find the reponse below.");
			error.setMessage(txnRespF + "\n" + txnRespS);
			OBContext.restorePreviousMode();
		} catch (JSONException e) {
			error.setType("error");
			error.setTitle("Error while reversing.");
			error.setMessage("Contact CAMPS Admin.");
			e.printStackTrace();
		}
		return error;
	}

	private OBError reverseInv(String invId) {
		OBError error = new OBError();
		StringBuilder valRes = new StringBuilder();
		Invoice invoice = OBDal.getInstance().get(Invoice.class, invId);
		OBCriteria<InvoiceLine> invLines = OBDal.getInstance().createCriteria(InvoiceLine.class);
		invLines.add(Restrictions.eq(InvoiceLine.PROPERTY_INVOICE, invoice));
		invLines.list().forEach(line -> {
			ShipmentInOut inout = line.getGoodsShipmentLine().getShipmentReceipt();
			log.info("InOut No: " + line.getGoodsShipmentLine().getShipmentReceipt().getDocumentNo());
			LocalDate datePur = inout.getMovementDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
			if ((qtrStart.compareTo(datePur) <= 0)) {
				log.info("Valid Purchase Date.");
			} // TODO Add this Condition later
			List<Asset> assetList = new ArrayList<Asset>();
			OBCriteria<Asset> critList = OBDal.getInstance().createCriteria(Asset.class);
			critList.add(Restrictions.eq(Asset.PROPERTY_AMMINOUTLINE, line.getGoodsShipmentLine()));
			assetList.addAll(critList.list());
			log.info("Number of Assets: " + assetList.size());
			assetList.forEach(asset -> {
				valRes.append(validateAsset(asset, false));
			});
		});

		if (valRes.length() == 0) {
			String GET_ACCT_LIST_HQL = "select e.id from ProcessRequest e where e.process.id='800064'";
			@SuppressWarnings("unchecked")
			List<String> acctList = OBDal.getInstance().getSession().createQuery(GET_ACCT_LIST_HQL).list();
			for (int a = 0; a < acctList.size(); a++) {
				OBContext.setAdminMode();
				ProcessRequest process = OBDal.getInstance().get(ProcessRequest.class, acctList.get(a));
				String status = process.getStatus();
				log.info("Status : " + status);
				if (status.matches("COM | UNS")) {
					log.info("Scheduler not active.");
				} else {
					log.info("Scheduler in Progress.");
					// error.setType("error");
					// error.setTitle("Acct Server Process is active.");
					// error.setMessage("Please Unschedule.");
					// return error;
				}
				OBContext.restorePreviousMode();
			}

			ArrayList<String> payDocs = new ArrayList<String>();
			ArrayList<FIN_Payment> pendPays = new ArrayList<FIN_Payment>();
			// TODO Handle pending payments
			try {
				OBCriteria<FIN_PaymentSchedule> finPay = OBDal.getInstance().createCriteria(FIN_PaymentSchedule.class);
				finPay.add(Restrictions.eq(FIN_PaymentSchedule.PROPERTY_INVOICE, invoice));
				for (int p = 0; p < finPay.list().size(); p++) {
					try {
						OBCriteria<FIN_PaymentScheduleDetail> finPaySh = OBDal.getInstance()
								.createCriteria(FIN_PaymentScheduleDetail.class);
						finPaySh.add(Restrictions.eq(FIN_PaymentScheduleDetail.PROPERTY_INVOICEPAYMENTSCHEDULE,
								finPay.list().get(p)));
						for (int u = 0; u < finPaySh.list().size(); u++) {
							FIN_Payment payment = finPaySh.list().get(u).getPaymentDetails().getFinPayment();
							if (payment.getStatus().equals("PWNC") && !payment.getDocumentNo().contains("*R*")
									&& payment.getPosted().equals("Y")) {
								pendPays.add(payment);
								payDocs.add(payment.getDocumentNo());
							}
						}
					} catch (Exception e) {
						e.printStackTrace();
						log.info("No Payment Schedule found. Processing Invoice.");
					}
				}
				if (pendPays.isEmpty()) {
					log.info("No Payments found. Processing Invoice.");
				} else {
					log.info("Payments found.");
					for (int p = 0; p < pendPays.size(); p++) {
						reversePayment(pendPays.get(p));
					}
					log.info("All the Payments were reversed. Processing Invoice.");
				}
			} catch (Exception e) {
				e.printStackTrace();
				log.info("No Payments found. Processing Invoice.");
			}
			// TODO Add condition to reverse invoice only when all the payment reversed
			if (true) {
				ErrorMessage status = reverseInvoice(invId);
				if (status.isStatus()) {
					error.setType("success");
					error.setTitle("Invoice Reversal Successful. - " + invoice.getDocumentNo());
					error.setMessage("Please handle the Postings.");
				} else {
					error.setType("success");
					error.setTitle(status.getMessage());
					error.setMessage(status.getDescription());
				}
			}
		} else {
			error.setType("error");
			error.setTitle("Error while Validating.");
			error.setMessage(valRes.toString());
		}
		return error;
	}

	private OBError reverseGR(String grId) {
		ShipmentInOut goodsRec = OBDal.getInstance().get(ShipmentInOut.class, grId);
		OBError error = new OBError();
		log.info("Goods Receipt " + goodsRec.getDocumentNo());
		LocalDate datePur = goodsRec.getMovementDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
		if ((qtrStart.compareTo(datePur) <= 0)) {
			log.info("Valid Purchase Date.");
		} else {
			log.info("Invalid Purchase Date.");
			// error.setType("error");
			// error.setTitle("Invalid Purchased Date.");
			// error.setMessage("Not belongs to this Quarter.");
		}

		// TODO Shift the following code in IF clause after testing.
		List<Asset> assetList = new ArrayList<Asset>();
		OBCriteria<Asset> critList = OBDal.getInstance().createCriteria(Asset.class);
		goodsRec.getMaterialMgmtShipmentInOutLineList().forEach(line -> {
			critList.add(Restrictions.eq(Asset.PROPERTY_AMMINOUTLINE, line));
			assetList.addAll(critList.list());
		});
		log.info("Number of Assets: " + assetList.size());
		StringBuilder valRes = new StringBuilder();
		assetList.forEach(asset -> {
			// StringBuilder msg = validateAsset(asset);
			valRes.append(validateAsset(asset, true));
		});
		if (valRes.length() == 0) {
			ErrorMessage msg = revUtil.deactivateGR(goodsRec);
			ErrorMessage tranx = new ErrorMessage();
			if (msg.isStatus()) {
				log.info("message:" + msg.getMessage());
				tranx = revUtil.reverseGRtransactionsWithOutTax(goodsRec);
				if (tranx.isStatus()) {
					error.setType("success");
					error.setTitle("GR Reversed Successfully.");
					error.setMessage("Please handle the Postings.");
				} else {
					log.info("Reversal Failed.");
					log.info("Message: " + tranx.getMessage());
					error.setType("error");
					error.setTitle("GR Reversal Failed.");
					error.setMessage(tranx.getMessage());
				}
			} else {
				log.info("Reversal Failed.");
				log.info("Message: " + msg.getMessage());
				error.setType("error");
				error.setTitle("GR Reversal Failed.");
				error.setMessage(tranx.getMessage());
			}
		} else {
			error.setType("error");
			error.setTitle("Error while Validating.");
			error.setMessage(valRes.toString());
		}
		// Till here

		return error;
	}

	public void reversePayment(FIN_Payment payment) {
		try {
			new FIN_PaymentProcess().processPayment(payment, "RV",
					new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(new Date()), null, null, true);
		} catch (Exception e) {
			e.printStackTrace();
			log.info("Payment is successful");
		}
	}

	public ErrorMessage reverseInvoice(String invId) {
		ErrorMessage msg = new ErrorMessage();
		Invoice invoice = OBDal.getInstance().get(Invoice.class, invId);
		invoice.setDocumentAction("RC");
		invoice.setPosted("N");
		invoice.setProcessNow(false);
		OBDal.getInstance().save(invoice);
		OBDal.getInstance().flush();
		log.info("Updated the Invoice." + invoice.getDocumentAction());
		try {
			if (invoice.getDocumentAction().equals("RC")) {
				log.info("Found Invoice - " + invoice.getDocumentNo());
				ErrorMessage msgvoid = reversal.voidInvoice(invoice);
				if (msgvoid.isStatus()) {
					invoice.setPosted("Y");
					OBDal.getInstance().save(invoice);
					OBDal.getInstance().flush();
					ErrorMessage msgcreate = reversal.createInvoice(invoice);
					if (msgcreate.isStatus()) {
						log.info("Created a new invoice ");
						String FIND_NEWINV_HQL = "select e.id from Invoice e where " + "e.documentNo='"
								+ invoice.getDocumentNo() + "'" + " and e.documentStatus='DR'";
						String id = (String) OBDal.getInstance().getSession().createQuery(FIND_NEWINV_HQL)
								.uniqueResult();
						Invoice newInvoice = OBDal.getInstance().get(Invoice.class, id);
						newInvoice.setDocumentNo("R" + invoice.getDocumentNo());
						OBDal.getInstance().save(newInvoice);
						OBDal.getInstance().flush();
						msg.setStatus(true);
					} else {
						log.info(msgcreate.isStatus() + ":" + msgcreate.getMessage());
						log.info("Invoice Creation is a failure");
						msg.setStatus(false);
						msg.setMessage("Invoice Reversal Failed.");
						msg.setDescription("Failed to Create the new Invoice.");
					}
				} else {
					log.info(msgvoid.isStatus() + ":" + msgvoid.getMessage());
					msg.setStatus(false);
					msg.setMessage("Invoice Reversal Failed.");
					msg.setDescription("Failed to Void the Invoice.");
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			log.info("Exception Occurred.");
			msg.setStatus(false);
			msg.setMessage("Invoice Reversal Failed.");
			msg.setDescription("Error while updating the Invoice.");
		}
		return msg;
	}

	private StringBuilder validateAsset(Asset asset, Boolean invCheck) {
		StringBuilder errMsg = new StringBuilder();
		List<String> dsInv = validate.checkInvoice(asset, true);
		List<String> dispDoc = validate.checkDisposal(asset);
		List<String> tranDoc = validate.checkTransfer(asset);
		List<String> dsOrd = validate.checkOrder(null, true, asset);
		Boolean isDisposed = validate.isDisposed(asset);
		Boolean isTransferred = validate.isTransferred(asset);
		final String checkInv = "select e.invoice.documentNo from InvoiceLine e "
				+ "where e.invoice.salesTransaction=false and e.invoice.documentStatus in ('DR','CO') "
				+ "and e.goodsShipmentLine.id = '" + asset.getAmMInoutline().getId() + "'";
		@SuppressWarnings("unchecked")
		List<String> pendInvList = OBDal.getInstance().getSession().createQuery(checkInv).list();
		if (!pendInvList.isEmpty() && invCheck) {
			log.info("Pending Purcase Invoice. Please void the Invoice");
			errMsg.append("\nPending/ Completed Purcahse Invoice found. -> " + LS(pendInvList));
		}
		errMsg.append(dsInv.isEmpty() ? "" : "\nPending Sales Invoice -> " + LS(dsInv));
		errMsg.append(dispDoc.isEmpty() ? "" : "\nPending Diposal -> " + LS(dispDoc));
		errMsg.append(tranDoc.isEmpty() ? "" : "\nPending Transfer -> " + LS(tranDoc));
		errMsg.append(dsOrd.isEmpty() ? "" : "\nPending Sales Order -> " + LS(dsOrd));
		errMsg.append((isDisposed) ? "\nAlready Disposed" : "");
		errMsg.append((isTransferred) ? "\nAlready Transferred" : "");
		return errMsg;
	}

	public String LS(List<String> list) {
		return (list.toString().replace("[", "").replace("]", "") + "\n");
	}
}