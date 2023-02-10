package com.splenta.admin.ad_process.reversals;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.collections.CollectionUtils;
import org.apache.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.jfree.util.Log;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.process.ProcessInstance;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.ReversedInvoice;
import org.openbravo.model.financialmgmt.accounting.AccountingFact;
import org.openbravo.model.financialmgmt.accounting.coa.ElementValue;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.service.db.CallProcess;

import com.chimera.finaclewebservice.ad_process.FinacleWebServiceUtility;
import com.chimera.fixedassetmanagement.ad_process.ErrorMessage;
import com.splenta.admin.ad_process.reversals.GSTUtility.Account;
import com.splenta.centralization.ad_process.CentralizationUtil;
import com.splenta.gst.GSTPOSTINGS;

public class InvoiceReversal {
	private static final Logger log4j = Logger.getLogger(InvoiceReversal.class);

	/**
	 * This method will void the invoice if all the check-list is passed 1)No
	 * payments should be available for the invoice.
	 * 
	 * @return
	 */
	public ErrorMessage updateInvoice(Invoice invoice) {
		ErrorMessage message = new ErrorMessage();

		if (invoice != null) {
			invoice.setDocumentAction("RC");
			invoice.setPosted("N");
			invoice.setProcessNow(false);
			OBDal.getInstance().save(invoice);
			message.setStatus(true);
			message.setMessage("Updated Successfully");
		} else {
			message.setStatus(false);
			message.setMessage("Invalid invoice id");
		}

		return message;
	}

	public ErrorMessage voidInvoice(Invoice invoice) {
		org.openbravo.model.ad.ui.Process process = OBDal.getInstance().get(org.openbravo.model.ad.ui.Process.class,
				"111");
		ErrorMessage message = callProcessGenricNoChecks(process, null, invoice.getId());
		return message;
	}

	/**
	 * Pass the old invoice. System will fetch the corresponding goods receipt and
	 * it will generate invoice from that
	 * 
	 * @param invoice
	 */
	public ErrorMessage createInvoice(Invoice invoice) {
		ErrorMessage msg = new ErrorMessage();
		OBCriteria<ShipmentInOut> shipCrt = OBDal.getInstance().createCriteria(ShipmentInOut.class);
		shipCrt.add(Restrictions.eq(ShipmentInOut.PROPERTY_DOCUMENTNO, invoice.getDocumentNo()));
		ShipmentInOut shipment = (ShipmentInOut) shipCrt.uniqueResult();
		if (shipment != null) {
			msg = new CentralizationUtil().createInvoice(invoice.getSalesOrder(), shipment.getId());
		}
		return msg;
	}

	/**
	 * 1)Disable the acct server process while doing the reversal 2)Un-post the
	 * invoice
	 *
	 * @param invoice
	 * @return
	 */
	public ErrorMessage validateChecks(Invoice invoice) {
		ErrorMessage msg = new ErrorMessage();
		if (invoice != null) {
			if (invoice.getPosted().equals("Y")) {
				// Return a message
				msg.setStatus(false);
				msg.setMessage("Please un-post the invoice");
			} else {
				msg.setStatus(true);
			}
		}

		return msg;
	}

	public ErrorMessage callProcessGenricNoChecks(org.openbravo.model.ad.ui.Process process,
			Map<String, String> parameters, String record_id) {
		ErrorMessage errormsg = new ErrorMessage();
		log4j.info("Record: " + record_id);
		try {
			boolean status = false;
			ProcessInstance pi = CallProcess.getInstance().callProcess(process, record_id, parameters);
			log4j.info("Result: " + pi.getResult());
			log4j.info("Error message: " + pi.getErrorMsg());

			if (pi.getResult() != null) {
				if (pi.getResult() == 1) {
					status = true;
					errormsg.setStatus(status);
					errormsg.setDocstatus("Success");
					errormsg.setMessage(pi.getErrorMsg());
				} else {
					errormsg.setStatus(status);
					errormsg.setMessage(pi.getErrorMsg());
				}

			} else {
				errormsg.setStatus(status);
				errormsg.setMessage(pi.getErrorMsg());
			}
			log4j.info("pInstance message:" + pi.getErrorMsg());

		} catch (Exception e) {
			e.printStackTrace();
		}
		return errormsg;
	}

	public ErrorMessage reverseInvoiceTransactionsWithOutTax(Invoice invoice) {
		ErrorMessage msg = new ErrorMessage();
		try {
			ElementValue creditacct = new ElementValue();
			ElementValue debitacct = new ElementValue();
			float amount = 0.0f;
			OBCriteria<AccountingFact> postingsCrt = OBDal.getInstance().createCriteria(AccountingFact.class);
			postingsCrt.add(Restrictions.eq(AccountingFact.PROPERTY_RECORDID, invoice.getId()));
			postingsCrt.add(Restrictions.eq(AccountingFact.PROPERTY_FWISFINPAYMENT, false));
			List<AccountingFact> lst = postingsCrt.list();
			if (CollectionUtils.isNotEmpty(lst)) {
				for (AccountingFact acct : lst) {
					if (acct.getCredit().doubleValue() > 0) {
						if (Objects.isNull(acct.getTax())) {
							creditacct = acct.getAccount();
							amount = amount + acct.getCredit().floatValue();
						} else {
							log4j.info("desc:" + acct.getDescription() + ":" + acct.getAccountingEntryDescription());
						}
					}

					if (acct.getDebit().doubleValue() > 0) {
						debitacct = acct.getAccount();
					}
				}
			}
			if (Objects.nonNull(creditacct) && Objects.nonNull(debitacct)) {

				boolean isDept = FinacleWebServiceUtility.isOrgDepartment(invoice.getOrganization().getId());

				String solidremitter = "";
				String solidbenificiary = "";

				if (isDept) {
					solidremitter = "0035";
					solidbenificiary = "0035";
				} else {
					solidremitter = invoice.getOrganization().getAMSolId();
					solidbenificiary = invoice.getOrganization().getAMSolId();
				}
				log4j.info(creditacct.getFwAccountNo() + ":" + debitacct.getFwAccountNo());
				msg = ReversalUtility.FinaclePosting(invoice.getOrganization().getId(), amount,
						debitacct.getFwAccountNo(), creditacct.getFwAccountNo(), "318", invoice.getId(), solidremitter,
						solidbenificiary, "REV", invoice.getDocumentNo());
				if (msg.isStatus()) {
					updateFactAccount(invoice.getId(), msg.getMessage());
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return msg;
	}

	// logic is wrong
	public ErrorMessage reverseInvoiceTransactionsWithTax(Invoice invoice, Invoice oldinvoice) {
		ErrorMessage msg = new ErrorMessage();
		try {
			ErrorMessage msgrev = reverseInvoiceTransactionsWithOutTax(invoice);
			if (msgrev.isStatus()) {
				// reverse the GST
				for (GSTPOSTINGS gst : oldinvoice.getGSTPOSTINGSList()) {
					Account acct = new GSTUtility().getaccountNumber(gst);
					ErrorMessage msgpostings = ReversalUtility.FinaclePosting(gst.getOrganization().getId(),
							gst.getAmount().floatValue(), acct.getCreditacct(), acct.getDebitacct(), "318",
							gst.getInvoice().getId(), acct.getRemsol(), acct.getBensol(), "REV", gst.getDocumentno());
					if (msgpostings.isStatus()) {
						gst.setType("REVERSAL");
						OBDal.getInstance().save(gst);
					}
				}

			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return msg;
	}

	public ErrorMessage reverseInvoiceTransactionsWithTaxNoRes(Invoice invoice, Invoice oldinvoice) {
		ErrorMessage msg = new ErrorMessage();
		try {

			// reverse the GST
			for (GSTPOSTINGS gst : oldinvoice.getGSTPOSTINGSList()) {
				Account acct = new GSTUtility().getaccountNumber(gst);
				ErrorMessage msgpostings = ReversalUtility.FinaclePosting(gst.getOrganization().getId(),
						gst.getAmount().floatValue(), acct.getCreditacct(), acct.getDebitacct(), "318",
						gst.getInvoice().getId(), acct.getRemsol(), acct.getBensol(), "REV", gst.getDocumentno());
				if (msgpostings.isStatus()) {
					gst.setType("REVERSAL");
					OBDal.getInstance().save(gst);
				}
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
		return msg;
	}

	public ErrorMessage reversePaymentTransactionsWithOutTax(FIN_Payment payment) {
		ErrorMessage msg = new ErrorMessage();
		try {
			if (payment != null) {
				ElementValue creditacct = new ElementValue();// Hard coded account as coms receivable
				ElementValue debitacct = OBDal.getInstance().get(ElementValue.class,
						"DEBD22C1D2384CDA82EA50868882E05A");
				String table_id = "";
				float amount = 0.0f;
				OBCriteria<AccountingFact> postingsCrt = OBDal.getInstance().createCriteria(AccountingFact.class);
				postingsCrt.add(Restrictions.eq(AccountingFact.PROPERTY_RECORDID, payment.getId()));
				postingsCrt.add(Restrictions.eq(AccountingFact.PROPERTY_FWISFINPAYMENT, false));
				List<AccountingFact> lst = postingsCrt.list();
				if (CollectionUtils.isNotEmpty(lst)) {
					for (AccountingFact acct : lst) {
						if (acct.getCredit().doubleValue() > 0) {
							creditacct = acct.getAccount();
							table_id = acct.getTable().getId();
							amount = acct.getCredit().floatValue();
						} else if (acct.getDebit().doubleValue() > 0 && acct.getTax() != null) {
							// In future work on it

						}
					}
				}
				if (Objects.nonNull(creditacct) && Objects.nonNull(debitacct)) {

					boolean isDept = FinacleWebServiceUtility.isOrgDepartment(payment.getOrganization().getId());

					String solidremitter = "";
					String solidbenificiary = "";

					if (isDept) {
						solidremitter = "0035";
						solidbenificiary = "0035";
					} else {
						solidremitter = payment.getOrganization().getAMSolId();
						solidbenificiary = payment.getOrganization().getAMSolId();
					}
					msg = ReversalUtility.FinaclePosting(payment.getOrganization().getId(), amount,
							debitacct.getFwAccountNo(), creditacct.getFwAccountNo(), table_id, payment.getId(),
							solidremitter, solidbenificiary, "REV", payment.getReferenceNo());
					if (msg.isStatus()) {
						updateFactAccount(payment.getId(), msg.getMessage());
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return msg;
	}

	public Invoice getOriginalInvoice(Invoice inv) {
		OBCriteria<ReversedInvoice> crt = OBDal.getInstance().createCriteria(ReversedInvoice.class);
		crt.add(Restrictions.eq(ReversedInvoice.PROPERTY_INVOICE, inv));
		ReversedInvoice rev = (ReversedInvoice) crt.uniqueResult();
		return rev.getReversedInvoice();
	}

	private void updateFactAccount(String record_id, String response) {
		try {
			String UPDATE_FACT_ACCOUNT_HQL = "update  FinancialMgmtAccountingFact f set  f.fwIsfinpayment = 'Y', f.fwFinacleResMsg='"
					+ response + "' where f.recordID='" + record_id + "'";
			int records = OBDal.getInstance().getSession().createQuery(UPDATE_FACT_ACCOUNT_HQL).executeUpdate();
			log4j.info("no of records updated: " + records);
		} catch (Exception exception) {
			log4j.info("Exception while Updating the Fact Account Table!" + exception.getMessage());
			exception.printStackTrace();
		}
	}

}
