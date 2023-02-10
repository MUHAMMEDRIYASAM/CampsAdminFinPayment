package com.splenta.admin.ad_process.bulkprocesses;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.collections.CollectionUtils;
import org.apache.log4j.Logger;
import org.hibernate.Query;
import org.hibernate.criterion.Restrictions;
import org.openbravo.advpaymentmngt.process.FIN_AddPaymentFromJournal;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.assetmgmt.AmortizationLine;
import org.openbravo.model.financialmgmt.assetmgmt.Asset;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.gl.GLBatch;
import org.openbravo.model.financialmgmt.gl.GLCategory;
import org.openbravo.model.financialmgmt.gl.GLJournal;
import org.openbravo.model.financialmgmt.gl.GLJournalLine;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.service.db.DalConnectionProvider;

import com.chimera.fixedassetmanagement.AmAssetDisposal;
import com.chimera.fixedassetmanagement.AmAssetDisposalLine;

public class DisposalAccounting {
	private final static Logger log = Logger.getLogger(DisposalAccounting.class);

	public static void pushEntrysToFactAcct(String disposalId, VariablesSecureApp vars) {
		AmAssetDisposal disp = OBDal.getInstance().get(AmAssetDisposal.class, disposalId);
		double amttemp = 0.0d;
		log.info("No. of Assets:" + disp.getAMASSETDISPOSALLINEList().size());
		for (AmAssetDisposalLine displine : disp.getAMASSETDISPOSALLINEList()) {
			log.info("Line Status:" + displine.getStatus());
			if (displine.getStatus().equals("H_AM_AP")) {
				String HQL_QUERY = "select aml from org.openbravo.model.financialmgmt.assetmgmt.AmortizationLine aml,org.openbravo.model.financialmgmt.assetmgmt.Amortization am where aml.asset.id='"
						+ displine.getAsset().getId()
						+ "' and aml.amortization.id = am.id  ORDER BY am.accountingDate desc";
				@SuppressWarnings("unchecked")
				List<AmortizationLine> amlist = OBDal.getInstance().getSession().createQuery(HQL_QUERY).list();
				/*
				 * Cases handling here 1)Asset with zero line called as fully
				 * depreciated 2)All other cases we are calculating the book
				 * value and we are posting
				 */
				Asset ast = displine.getAsset();
				if (amlist.size() > 0) {
					log.info("Size is greaterthan zero");
					amttemp = amttemp + (amlist.get(0).getAMBookValue().doubleValue()
							- amlist.get(0).getAmortizationAmount().doubleValue());
					ast.setAmBookvalAtsale(
							(amlist.get(0).getAMBookValue().subtract(amlist.get(0).getAmortizationAmount())));
					// Added by vikas for Demoralization profit and loss data
					if (disp.getWriteoff().equals("writeoff")) {
						ast.setIlastSaleValue(BigDecimal.ZERO);
					}
					ast.setResidualAssetValue(new BigDecimal(0.0));
					ast.setDepreciationAmt(new BigDecimal(0.0));
					ast.setDisposed(true);
					ast.setAMISIDLE(false);
					ast.setAssetDisposalDate(java.sql.Date.valueOf(LocalDate.now()));
					ast.setUpdatedBy(disp.getUpdatedBy());
					ast.setILASTDateOfSale(java.sql.Date.valueOf(LocalDate.now()));
					ast.setStatic(false);
					ast.setProfit(ast.getIlastSaleValue().subtract(ast.getAmBookvalAtsale()));
					ast.setAmBrokenDay(amlist.get(0).getAmortizationAmount());
					OBDal.getInstance().save(ast);
					log.info("Book value: " + amlist.get(0).getAMBookValue().doubleValue());
					log.info("Broken Day Amount: " + amlist.get(0).getAmortizationAmount().doubleValue());
				} else {
					// Calculate the residual value
					double residualval = 0.0d;
					String deptype = "";
					double depvalue = 0.0d;
					try {
						OBContext.setAdminMode();
						deptype = ast.getProduct().getAmAssetType().getAmdepreciationtype().toString();
						depvalue = ast.getProduct().getAmAssetType().getAmdepreciationtypevalue().doubleValue();
						if (deptype.equalsIgnoreCase("VAL")) {
							residualval = depvalue;
							amttemp = amttemp + residualval;
						} else {
							double addcost = 0.0;
							double credittaken = 0.0;
							double assetval = 0.0;
							if (ast.getIlastAdditionalCost() != null) {
								addcost = ast.getIlastAdditionalCost().doubleValue();
							}
							if (displine.getAsset().getIlastCreditTaken() != null) {
								credittaken = ast.getIlastCreditTaken().doubleValue();
							}
							assetval = ((ast.getIlastPurchaseCost().doubleValue() + addcost) - credittaken);
							residualval = assetval * (depvalue / 100);
							log.info("final residualval:" + residualval);
							amttemp = amttemp + residualval;
							ast.setAmBookvalAtsale(new BigDecimal(residualval));
							OBDal.getInstance().save(ast);
						}
					} catch (Exception e) {
						e.printStackTrace();
					} finally {
						OBContext.restorePreviousMode();
					}
				}

			}
		}
		BigDecimal amount = new BigDecimal(amttemp);
		log.info("amount which is going to post in disposal :" + amount);
		//
		try {
			OBContext.setAdminMode();
			// Code for advance Payment
			// Creating a General Ledger
			String clientId = OBContext.getOBContext().getCurrentClient().getId();
			Period currentPeriod = OBProvider.getInstance().get(Period.class);
			Calendar firstDateOfMonth = Calendar.getInstance();
			firstDateOfMonth.set(Calendar.DAY_OF_MONTH, 1);

			String HQL_C_PERIOD = "select p from org.openbravo.model.financialmgmt.calendar.Period p where p.startingDate = :Date"
					+ " and p.client.id = '" + clientId + "'";

			Query query = OBDal.getInstance().getSession().createQuery(HQL_C_PERIOD);
			query.setDate("Date", firstDateOfMonth.getTime());
			currentPeriod = (Period) query.uniqueResult();
			log.info("Period : " + currentPeriod.getName());

			GLBatch glbatch = OBProvider.getInstance().get(GLBatch.class);
			// GLBatch glbatch1=new GLBatch();
			final OBCriteria<GLBatch> glbatchList = OBDal.getInstance().createCriteria(GLBatch.class);
			glbatchList.add(Restrictions.eq(GLBatch.PROPERTY_ORGANIZATION, disp.getOrganization()));
			glbatchList.add(Restrictions.eq(GLBatch.PROPERTY_PERIOD, currentPeriod));

			final OBCriteria<GLCategory> glCatList = OBDal.getInstance().createCriteria(GLCategory.class);
			glCatList.add(Restrictions.eq(GLCategory.PROPERTY_NAME, "Standard"));
			GLCategory glCat = glCatList.list().get(0);
			if (CollectionUtils.isNotEmpty(glbatchList.list())) {
				// Get the first Object
				glbatch = glbatchList.list().get(0);
				log.info("Found GL Batch :" + glbatchList.list().size());
			} else {
				try {
					glbatch.setAccountingDate(java.sql.Date.valueOf(LocalDate.now()));
					glbatch.setPeriod(currentPeriod);
					log.info("Organization : " + disp.getOrganization().getName());
					glbatch.setOrganization(disp.getOrganization());
					glbatch.setDescription(disp.getOrganization() + " Journal " + firstDateOfMonth.getTime());
					glbatch.setCurrency(OBContext.getOBContext().getCurrentClient().getCurrency());
					glbatch.setProcessed(false);

					String strDocumentno = Utility.getDocumentNo(new DalConnectionProvider(),
							OBContext.getOBContext().getCurrentClient().getId(), "GL_JournalBatch", true);
					glbatch.setDocumentNo(strDocumentno);
					glbatch.setDocumentDate(java.sql.Date.valueOf(LocalDate.now()));
					glbatch.setGLCategory(glCat);
					OBDal.getInstance().save(glbatch);
					OBDal.getInstance().flush();
				} catch (Exception e) {
					e.printStackTrace();
				}

			}

			GLJournal journalHeader = OBProvider.getInstance().get(GLJournal.class);

			journalHeader.setAccountingDate(java.sql.Date.valueOf(LocalDate.now()));
			journalHeader.setOrganization(disp.getOrganization());
			journalHeader.setPeriod(currentPeriod);
			final OBCriteria<AcctSchema> acctSchemaList = OBDal.getInstance().createCriteria(AcctSchema.class);
			acctSchemaList.add(Restrictions.eq(AcctSchema.PROPERTY_NAME, "Federal Bank"));

			journalHeader.setAccountingSchema(acctSchemaList.list().get(0));
			journalHeader.setDescription(disp.getId());
			journalHeader.setCurrency(OBContext.getOBContext().getCurrentClient().getCurrency());
			journalHeader.setDocumentDate(java.sql.Date.valueOf(LocalDate.now()));
			final OBCriteria<DocumentType> docTypeList = OBDal.getInstance().createCriteria(DocumentType.class);
			docTypeList.add(Restrictions.eq(DocumentType.PROPERTY_NAME, "GL Journal"));
			journalHeader.setDocumentType(docTypeList.list().get(0));
			journalHeader.setPostingType("A");
			journalHeader.setGLCategory(glCat);
			journalHeader.setProcessed(false);
			String strDocumentno = Utility.getDocumentNo(new DalConnectionProvider(),
					OBContext.getOBContext().getCurrentClient().getId(), "GL_Journal", true);
			journalHeader.setDocumentNo(strDocumentno);

			journalHeader.setJournalBatch(glbatch);
			OBDal.getInstance().save(journalHeader);
			OBDal.getInstance().flush();
			glbatch.getFinancialMgmtGLJournalList().add(journalHeader);
			// Here delete the loop only one record should insert with the
			// sample value

			List<GLJournalLine> journalLines = new ArrayList<GLJournalLine>();
			GLJournalLine glJournalLineDebit = OBProvider.getInstance().get(GLJournalLine.class);
			glJournalLineDebit.setOrganization(disp.getOrganization());
			glJournalLineDebit.setAccountingDate(java.sql.Date.valueOf(LocalDate.now()));
			glJournalLineDebit.setLineNo(new Long(10));
			glJournalLineDebit.setCurrency(OBContext.getOBContext().getCurrentClient().getCurrency());
			// The below two lines should change
			glJournalLineDebit.setDebit(amount);
			glJournalLineDebit.setForeignCurrencyDebit(amount);
			// Up to here
			glJournalLineDebit.setForeignCurrencyCredit(new BigDecimal(0));
			glJournalLineDebit.setCredit(new BigDecimal(0));
			glJournalLineDebit.setDescription("Postings for Write Off" + disp.getDocumentNo());
			// TODO Need to change according the requirement
			String validcomb_sqldr = "select cv.c_validcombination_id from c_elementvalue ce inner join C_ValidCombination cv on "
					+ "cv.account_id=ce.c_elementvalue_id where ce.em_fw_account_no='0011541001' and rownum<2";
			String accid = (String) OBDal.getInstance().getSession().createSQLQuery(validcomb_sqldr).uniqueResult();
			log.info("accid :" + accid);
			AccountingCombination deditbAcc = OBDal.getInstance().get(AccountingCombination.class, accid);

			glJournalLineDebit.setAccountingCombination(deditbAcc);
			glJournalLineDebit.setJournalEntry(journalHeader);
			OBDal.getInstance().save(glJournalLineDebit);

			GLJournalLine glJournalLineCredit = OBProvider.getInstance().get(GLJournalLine.class);
			glJournalLineCredit.setOrganization(disp.getOrganization());
			glJournalLineCredit.setAccountingDate(java.sql.Date.valueOf(LocalDate.now()));
			glJournalLineCredit.setLineNo(new Long(20));
			glJournalLineCredit.setCurrency(OBContext.getOBContext().getCurrentClient().getCurrency());
			glJournalLineCredit.setCredit(amount);
			glJournalLineCredit.setDebit(new BigDecimal(0));
			glJournalLineCredit.setForeignCurrencyCredit(amount);
			glJournalLineCredit.setForeignCurrencyDebit(new BigDecimal(0));
			glJournalLineCredit.setDescription("Write Off Document No:" + disp.getDocumentNo());
			// TODO Need to change according the requirement
			String validcomb_sqlcr = "select cv.c_validcombination_id from c_elementvalue ce inner join C_ValidCombination cv on "
					+ "cv.account_id=ce.c_elementvalue_id where ce.em_fw_account_no='0091030001' and rownum<2";
			String accidcr = (String) OBDal.getInstance().getSession().createSQLQuery(validcomb_sqlcr).uniqueResult();
			log.info("accidcr :" + accidcr);
			AccountingCombination creditAcc = OBDal.getInstance().get(AccountingCombination.class, accidcr);
			glJournalLineCredit.setAccountingCombination(creditAcc);
			glJournalLineCredit.setJournalEntry(journalHeader);

			journalLines.add(glJournalLineCredit);
			OBDal.getInstance().save(glJournalLineCredit);
			OBDal.getInstance().flush();

			log.info("glJournalLineCredit " + glJournalLineCredit + " ,glJournalLineDebit " + glJournalLineDebit);

			ProcessBundle pb = new ProcessBundle("5BE14AA10165490A9ADEFB7532F7FA94", vars);
			HashMap<String, Object> parameters = new HashMap<String, Object>();
			parameters.put("GL_Journal_ID", journalHeader.getId());
			pb.setParams(parameters);

			log.info("Executing FIN_AddPaymentFromJournal . . .");
			FIN_AddPaymentFromJournal finProcess = new FIN_AddPaymentFromJournal();
			finProcess.execute(pb);

			// Change it to different doctype
		} catch (Exception e) {
			log.info("WARNING > " + e);
			e.printStackTrace();
		} finally {
			OBContext.restorePreviousMode();
		}
	}
}