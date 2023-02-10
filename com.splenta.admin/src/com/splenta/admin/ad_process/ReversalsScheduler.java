package com.splenta.admin.ad_process;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.apache.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.accounting.AccountingFact;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.service.db.DalBaseProcess;

import com.chimera.fixedassetmanagement.ad_process.ErrorMessage;
import com.chimera.fixedassetmanagement.ad_process.ProcessPurchaseInvoice;
import com.splenta.admin.ad_process.reversals.InvoiceReversal;

public class ReversalsScheduler extends DalBaseProcess {
	private static final Logger log = Logger.getLogger(ReversalsScheduler.class.getName());
	private static ProcessBundle logbundle;
	InvoiceReversal reversal = WeldUtils.getInstanceFromStaticBeanManager(InvoiceReversal.class);

	@Override
	protected void doExecute(ProcessBundle bundle) throws Exception {
		logbundle = bundle;
		log.info("Revesals Scheduler in charge...");
		String docType[] = { "ED1E059C9CF54062BA43B79F3EB89C77", "AFFD4594FB734E9682D5769669A9D471" };
		/** @DocumentType AP Payment */
		ArrayList<String> payDocs = new ArrayList<String>();
		ArrayList<FIN_Payment> pendPays = new ArrayList<FIN_Payment>();
		ArrayList<String> invDocs = new ArrayList<String>();
		ArrayList<Invoice> pendInvs = new ArrayList<Invoice>();

		final String REV_PAY_HQL = "select distinct e.recordID from FinancialMgmtAccountingFact e "
				+ "where e.fwFinpayInprocess=false and e.fwIsfinpayment=false and e.documentType.id='" + docType[0]
				+ "'";
		@SuppressWarnings("unchecked")
		List<String> payList = OBDal.getInstance().getSession().createQuery(REV_PAY_HQL).list();

		log.info("Pending Payments : " + payList.size());
		for (int j = 0; j < payList.size(); j++) {
			FIN_Payment finPay = OBDal.getInstance().get(FIN_Payment.class, payList.get(j));
			if (finPay.getStatus().equals("PWNC") && finPay.getDocumentNo().contains("*R*")) {
				payDocs.add(finPay.getDocumentNo());
				pendPays.add(finPay);
			}
		}

		if (!pendPays.isEmpty()) {
			log.info("Pending Reversal Payments " + payDocs);
			bundle.getLogger().logln("Pending Reversed Payments " + payDocs);
			reversePaymentPostings(pendPays);
		} else {
			log.info("Found no pending reversal payments.");
			bundle.getLogger().logln("Found no pending reversal payments.");
		}

		/** @DocumentType Reversed Purchase Invoice */
		final String REV_INV_HQL = "select distinct e.recordID from FinancialMgmtAccountingFact e "
				+ "where e.fwFinpayInprocess=false and e.fwIsfinpayment=false and e.documentType.id='" + docType[1]
				+ "'";
		@SuppressWarnings("unchecked")
		List<String> invList = OBDal.getInstance().getSession().createQuery(REV_INV_HQL).list();

		log.info("Pending Invoices : " + invList.size());
		for (int j = 0; j < invList.size(); j++) {
			Invoice finInv = OBDal.getInstance().get(Invoice.class, invList.get(j));
			if (finInv.getDocumentStatus().equals("VO")) {
				invDocs.add(finInv.getDocumentNo());
				pendInvs.add(finInv);
			}
		}

		if (!pendInvs.isEmpty()) {
			log.info("Pending Reversal Invoices " + invDocs);
			bundle.getLogger().logln("Pending Reversed Invoices " + invDocs);
			reverseInvoicePostings(pendInvs);
		} else {
			log.info("Found no pending reversal invoices.");
			bundle.getLogger().logln("Found no pending reversal invoices.");
		}

	}

	public void reversePaymentPostings(ArrayList<FIN_Payment> pendPays) {
		int revCount = 0;
		for (int t = 0; t < pendPays.size(); t++) {
			String payDoc = pendPays.get(t).getDocumentNo() + " - Payment Reversal";
			String txnStatus;
			ErrorMessage msg = reversal.reversePaymentTransactionsWithOutTax(pendPays.get(t));
			txnStatus = payDoc + " " + (msg.isStatus() ? "SUCCESS" : "FAILED");
			revCount = revCount + (msg.isStatus() ? 1 : 0);
			log.info(msg.isStatus() + ":" + msg.getMessage());
			log.info(txnStatus);
			logbundle.getLogger().logln(txnStatus);
		}
		log.info("Paid " + revCount + " Payments out of " + pendPays.size());
		logbundle.getLogger().logln("Paid " + revCount + " Payments out of " + pendPays.size());
	}

	public void reverseInvoicePostings(ArrayList<Invoice> pendInvs) {
		int revCount = 0;
		for (int t = 0; t < pendInvs.size(); t++) {
			String invDoc = pendInvs.get(t).getDocumentNo() + "- Invoice Reversal";
			String txnStatus;
			ErrorMessage msg = new ErrorMessage();
			Invoice revInv = pendInvs.get(t);
			Invoice orgInv = reversal.getOriginalInvoice(revInv);
			log.info("Rev Invoice: " + revInv.getDocumentNo());
			log.info("Old Invoice: " + orgInv.getDocumentNo());
			// FIXME if any invoice is having both exempt and gst it will go to without tax
			boolean status = new ProcessPurchaseInvoice().restrictExemptInvoices(orgInv);
			log.info("Posting ..." + revInv.getDocumentNo());
			if (status) {
				log.info("Invoice is having GST");
				msg = reversal.reverseInvoiceTransactionsWithTax(revInv, orgInv);
				txnStatus = invDoc + " " + (msg.isStatus() ? "SUCCESS" : "FAILED");
				revCount = revCount + (msg.isStatus() ? 1 : 0);
			} else {
				log.info("Invoice tax is Exempt");
				msg = reversal.reverseInvoiceTransactionsWithOutTax(revInv);
				txnStatus = invDoc + " " + (msg.isStatus() ? "SUCCESS" : "FAILED");
				revCount = revCount + (msg.isStatus() ? 1 : 0);
			}
			log.info(msg.isStatus() + ":" + msg.getMessage());
			log.info(txnStatus);
			logbundle.getLogger().logln(txnStatus);
		}
		log.info("Paid " + revCount + " Invoices out of " + pendInvs.size());
		logbundle.getLogger().logln("Paid " + revCount + " Invoices out of " + pendInvs.size());
	}
}