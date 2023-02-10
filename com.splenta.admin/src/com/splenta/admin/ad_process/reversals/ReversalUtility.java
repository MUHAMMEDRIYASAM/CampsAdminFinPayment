package com.splenta.admin.ad_process.reversals;

import java.math.BigDecimal;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.map.HashedMap;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.log4j.Logger;
import org.hibernate.NonUniqueResultException;
import org.hibernate.Query;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.process.ProcessInstance;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.financialmgmt.accounting.AccountingFact;
import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.accounting.coa.ElementValue;
import org.openbravo.model.financialmgmt.assetmgmt.AmortizationLine;
import org.openbravo.model.financialmgmt.assetmgmt.Asset;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.gl.GLBatch;
import org.openbravo.model.financialmgmt.gl.GLCategory;
import org.openbravo.model.financialmgmt.gl.GLJournal;
import org.openbravo.model.financialmgmt.gl.GLJournalLine;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.service.db.CallProcess;
import org.openbravo.service.db.DalConnectionProvider;

import com.chimera.finaclewebservice.ad_process.FinaclePostings;
import com.chimera.finaclewebservice.ad_process.FinacleWebServiceUtility;
import com.chimera.finaclewebservice.ws.main.FederalBankPayment;
import com.chimera.finaclewebservice.ws.vo.FinPaymentVO;
import com.chimera.finaclewebservice.ws.vo.RequestVO;
import com.chimera.finaclewebservice.ws.vo.ResponseVO;
import com.chimera.finaclewebservice.wsobjects.BeneficiaryDetails;
import com.chimera.finaclewebservice.wsobjects.RemitterDetails;
import com.chimera.fixedassetmanagement.AmAssetDisposal;
import com.chimera.fixedassetmanagement.AmAssetDisposalLine;
import com.chimera.fixedassetmanagement.AmFinacleLog;
import com.chimera.fixedassetmanagement.AmPayment;
import com.chimera.fixedassetmanagement.ad_process.ErrorMessage;
import com.chimera.fixedassetmanagement.ad_process.GSTReAmortization;
import com.chimera.fixedassetmanagement.ad_process.ProcessPurchaseInvoice;
import com.chimera.fixedassetmanagement.ad_process.ReAmortization;
import com.chimera.fixedassetmanagement.ad_process.SaleUtil;
import com.chimera.fixedassetmanagement.ad_process.SaleUtil.ACCOUNTTYPE;
import com.splenta.admin.ad_process.reversals.GSTUtility.Account;
import com.splenta.gst.GSTPOSTINGS;
import com.splenta.pmn.PremisesPayment;
import com.splenta.pmn.PrmWorkHistory;
import com.splenta.pmn.ad_process.ProcessCompletePayment;
import com.splenta.pmn.ad_process.JavaClasses.PrmPayment;
import com.splenta.pmn.ad_process.WorkOrderPayment.UpdateOrderAndPayment;
import com.splenta.pns.ad_process.GSTPostingsToFinacle;

/*This class file is useful for running custom scripts in live */
/**
 * @author vikas_splenta
 *
 */
public class ReversalUtility {
	private final static Logger log4j = Logger.getLogger(ReversalUtility.class);
	private static SimpleDateFormat simpleDateFormat = new SimpleDateFormat("MMSS");
	public static final BigDecimal ONEHUNDERT = new BigDecimal(100);
	private final static String senderRefId = "CAMPS";
	private final static Date curDate = java.sql.Date.valueOf(LocalDate.now());

	public static ErrorMessage postIPCR(String documentno) {
		StringBuffer sb = new StringBuffer();
		ErrorMessage message = new ErrorMessage();

		if (documentno != null && !documentno.isEmpty()) {
			final OBQuery<Invoice> obQuery = OBDal.getInstance().createQuery(Invoice.class,
					"documentNo='" + documentno + "' ");
			Invoice bpgs = obQuery.uniqueResult();
			if (bpgs != null) {
				message = GSTPostingsToFinacle.isIPCRpaid(bpgs);
				sb.append("Status of IPCR:" + message.isStatus());
				if (message.isStatus() == true) {
					OBCriteria<GSTPOSTINGS> postingsCrt = OBDal.getInstance().createCriteria(GSTPOSTINGS.class);
					postingsCrt.add(Restrictions.eq(GSTPOSTINGS.PROPERTY_INVOICE, bpgs));
					postingsCrt.add(Restrictions.eq(GSTPOSTINGS.PROPERTY_FINACLPOST, false));
					postingsCrt.add(Restrictions.eq(GSTPOSTINGS.PROPERTY_ISGL, true));
					List<GSTPOSTINGS> postingpending = postingsCrt.list();
					if (CollectionUtils.isNotEmpty(postingpending)) {
						for (GSTPOSTINGS gst : postingpending) {
							ElementValue debitacct = SaleUtil.getFixedAssetAccountFromInvoice(bpgs,
									ACCOUNTTYPE.FIXEDASSET);

							ElementValue creditacct = GSTPostingsToFinacle.getTaxAccountSchema(gst);
							sb.append("calling finacle posting ");
							if (FinacleWebServiceUtility.isOrgDepartment(gst.getOrganization().getId())) {

								String solid = "0035";
								ErrorMessage msg = FinaclePosting(gst.getOrganization().getId(),
										gst.getAmount().floatValue(), debitacct.getFwAccountNo(),
										creditacct.getFwAccountNo(), "318", gst.getInvoice().getId(), solid,
										GSTPostingsToFinacle.getNodalSolId(gst.getOrganization()), "GST",
										gst.getDocumentno());
								if (msg.isStatus()) {
									gst.setFinaclpost(true);
									OBDal.getInstance().save(gst);
								}
								message = msg;
							} else {
								ErrorMessage msg = FinaclePosting(gst.getOrganization().getId(),
										gst.getAmount().floatValue(), debitacct.getFwAccountNo(),
										creditacct.getFwAccountNo(), "318", gst.getInvoice().getId(),
										gst.getOrganization().getAMSolId(), gst.getOrganization().getAMSolId(), "GST",
										gst.getDocumentno());
								if (msg.isStatus()) {
									gst.setFinaclpost(true);
									OBDal.getInstance().save(gst);
								}
								message = msg;
							}

						}

					}

				}
			}

		} else {
			message.setStatus(false);
			message.setMessage("Invalid documentno ");
		}
		message.setDescription(message.getDescription() + " " + sb.toString());
		return message;
	}

	public ErrorMessage reverseIPCR(String documentno) {
		StringBuffer sb = new StringBuffer();
		ErrorMessage message = new ErrorMessage();

		if (documentno != null && !documentno.isEmpty()) {
			final OBQuery<Invoice> obQuery = OBDal.getInstance().createQuery(Invoice.class,
					"documentNo='" + documentno + "' ");
			Invoice bpgs = obQuery.uniqueResult();
			if (bpgs != null) {
				OBCriteria<GSTPOSTINGS> postingsCrt = OBDal.getInstance().createCriteria(GSTPOSTINGS.class);
				postingsCrt.add(Restrictions.eq(GSTPOSTINGS.PROPERTY_INVOICE, bpgs));
				postingsCrt.add(Restrictions.eq(GSTPOSTINGS.PROPERTY_ISGL, true));
				postingsCrt.add(Restrictions.isNotNull(GSTPOSTINGS.PROPERTY_INVOICETAX));

				List<GSTPOSTINGS> postingpending = postingsCrt.list();
				if (CollectionUtils.isNotEmpty(postingpending)) {
					log4j.info("Total IPCR Count:" + postingpending.size() + "");
					for (GSTPOSTINGS gst : postingpending) {
						ElementValue creditacct = SaleUtil.getFixedAssetAccountFromInvoice(bpgs,
								ACCOUNTTYPE.FIXEDASSET);
						ElementValue debitacct = GSTPostingsToFinacle.getTaxAccountSchema(gst);
						sb.append("calling finacle posting ");
						if (FinacleWebServiceUtility.isOrgDepartment(gst.getOrganization().getId())) {
							String solid = "0035";
							ErrorMessage msg = FinaclePosting(gst.getOrganization().getId(),
									gst.getAmount().floatValue(), debitacct.getFwAccountNo(),
									creditacct.getFwAccountNo(), "318", gst.getInvoice().getId(),
									GSTPostingsToFinacle.getNodalSolId(gst.getOrganization()), solid, "GST",
									gst.getDocumentno());
							if (msg.isStatus()) {
								gst.setType("REVERSAL");
								OBDal.getInstance().save(gst);
							}
							message = msg;
						} else {
							ErrorMessage msg = FinaclePosting(gst.getOrganization().getId(),
									gst.getAmount().floatValue(), debitacct.getFwAccountNo(),
									creditacct.getFwAccountNo(), "318", gst.getInvoice().getId(),
									gst.getOrganization().getAMSolId(), gst.getOrganization().getAMSolId(), "GST",
									gst.getDocumentno());
							if (msg.isStatus()) {
								gst.setType("REVERSAL");
								OBDal.getInstance().save(gst);
							}
							message = msg;
						}
					}
				} else {
					log4j.info("NO IPCR is available");
				}
			}
		} else {
			message.setStatus(false);
			message.setMessage("Invalid documentno ");
		}
		message.setDescription(message.getDescription() + " " + sb.toString());
		return message;
	}

	public ErrorMessage reverseDuplicateGST(GSTPOSTINGS gst) {
		Account acct = new GSTUtility().getaccountNumber(gst);
		ErrorMessage msg = FinaclePosting(gst.getOrganization().getId(), gst.getAmount().floatValue(),
				acct.getCreditacct(), acct.getDebitacct(), "318", gst.getInvoice().getId(), acct.getRemsol(),
				acct.getBensol(), "REV", gst.getDocumentno());
		if (msg.isStatus()) {
			gst.setType("REVERSAL");
			OBDal.getInstance().save(gst);
		}
		return msg;
	}

	public static ErrorMessage FinaclePosting(String orgid, float amount, String remitterAccountNumber,
			String benificiaryAccountNumber, String tableid, String factid, String remsolid, String bensolid,
			String tag, String documentno) {
		// OBContext.setAdminMode();
		StringBuilder sb = new StringBuilder();
		ErrorMessage message = new ErrorMessage();
		sb.append("remitterAccountNumber:" + remitterAccountNumber);
		sb.append("benificiaryAccountNumber:" + benificiaryAccountNumber);
		sb.append("ID:" + factid);
		FinPaymentVO finPaymentVO = new FinPaymentVO();
		RequestVO requestVO = new RequestVO();

		Organization oL = OBDal.getInstance().get(Organization.class, orgid);
		finPaymentVO.setTableId(tableid);
		finPaymentVO.setRecordId(factid);// set the record id here
		finPaymentVO.setOrgId(orgid);
		FederalBankPayment.initload();
		try {
			sb.append("Username:" + FederalBankPayment.properties.getProperty("username"));
			requestVO.setUsername("" + FederalBankPayment.properties.getProperty("username"));
			requestVO.setPassword("" + FederalBankPayment.properties.getProperty("password"));
			requestVO.setSenderId("" + FederalBankPayment.properties.getProperty("senderid"));
		} catch (Exception e) {
			sb.append("Error in fetching properties ");
			sb.append("Check finacle.property file in tomcat web-inf classes folder");
			e.printStackTrace();
			throw new OBException("Error while fetching properties ");
		}
		requestVO.setMsgTypeId(FinacleWebServiceUtility.FUND_TRANSFER);
		requestVO.setChannelID(FinacleWebServiceUtility.CHANNEL_ID);
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
		requestVO.setMsgSendDate(dateFormat.format(curDate));
		try {
			requestVO.setCurrencyCode("" + oL.getCurrency().getISOCode());
			sb.append("Currency:" + oL.getCurrency().getISOCode());
		} catch (Exception e) {
			sb.append("Error in getting currency");
			throw new OBException("Error while fetching properties ");
		}
		SimpleDateFormat currentDate = new SimpleDateFormat("MMyy");
		sb.append("currentDate:" + currentDate.format(new Date()));
		int randomWithMathRandom = (int) ((Math.random() * (999 - 100)) + 100);
		String currentDateSec = simpleDateFormat.format(curDate).toString();
		log4j.info("SenderRefId > " + senderRefId + "" + tag + documentno + "" + currentDateSec);
		log4j.info("SenderRefId > " + senderRefId + "" + tag + documentno + "" + randomWithMathRandom);
		requestVO.setSenderRefId(senderRefId + "" + tag + documentno + "" + randomWithMathRandom);

		String[] senderrec = { "Custom Finacle Posting" };
		requestVO.setSenderToReceiverInfo(senderrec);
		requestVO.setTransferAmount("" + amount);
		sb.append("Trasfer Amount:" + amount);
		RemitterDetails remitterDetails = new RemitterDetails();
		BeneficiaryDetails benificiaryDetails = new BeneficiaryDetails();
		benificiaryDetails.setBeneficiaryAccType("10");
		remitterDetails.setRemitterAccNo(remsolid + "" + remitterAccountNumber);
		benificiaryDetails.setBeneficiaryAccNo(bensolid + "" + benificiaryAccountNumber);
		String benname = getAccountName(benificiaryAccountNumber);
		if (benname != null) {
			benificiaryDetails.setBeneficiaryName(benname);
		} else {
			benificiaryDetails.setBeneficiaryName("" + oL.getName());
			sb.append("<ERROR>INVALID ACCOUNT NUMBER<ERROR>");
			sb.append("\n");
		}
		String remname = getAccountName(remitterAccountNumber);
		if (remname != null) {
			remitterDetails.setRemitterName(remname);
		} else {
			sb.append("<ERROR>INVALID REMITTER ACCOUNT NUMBER<ERROR>");
			sb.append("\n");
			remitterDetails.setRemitterName("" + oL.getName());
		}

		try {
			OrganizationInformation orgerror = oL.getOrganizationInformationList().get(0);
			if (orgerror.getLocationAddress() != null) {
				StringBuffer buffer = new StringBuffer();
				buffer.append(orgerror.getLocationAddress().getAddressLine1());
				buffer.append(orgerror.getLocationAddress().getAddressLine2());
				buffer.append(orgerror.getLocationAddress().getCityName());
				buffer.append(orgerror.getLocationAddress().getPostalCode());
				buffer.append(orgerror.getLocationAddress().getRegionName());

				if (buffer.length() > 35) {
					String[] address = { buffer.toString().substring(0, 33) };
					remitterDetails.setRemitterAddress(address);
					benificiaryDetails.setBeneficiaryAddress(address);
					benificiaryDetails.setBeneficiaryBankAddress(address);
					sb.append("Address :" + address);
				} else {
					String[] address = { buffer.toString() };
					remitterDetails.setRemitterAddress(address);
					benificiaryDetails.setBeneficiaryAddress(address);
					benificiaryDetails.setBeneficiaryBankAddress(address);
					sb.append("Address :" + address);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			sb.append("Error Occured while fetching the benificiary and remitter details:");
		}
		sb.append("REMITTER AND BENIFICIARY DETAILS" + "\n");
		sb.append("getRemitterAccNo:" + remitterDetails.getRemitterAccNo() + "\n");
		sb.append("getRemitterName:" + remitterDetails.getRemitterName() + "\n");
		sb.append("REMITTER ADDRESS:" + remitterDetails.getRemitterAddress() + "\n");
		sb.append("benificiaryDetails AccNo:" + benificiaryDetails.getBeneficiaryAccNo() + "\n");
		sb.append(" benificiaryDetails Name:" + benificiaryDetails.getBeneficiaryName() + "\n");
		sb.append(" benificiaryDetails  ADDRESS:" + benificiaryDetails.getBeneficiaryAddress() + "\n");
		requestVO.setRemitterDetails(remitterDetails);
		requestVO.setBeneficiaryDetails(benificiaryDetails);
		finPaymentVO.setRequestVO(requestVO);
		try {
			OBContext.setAdminMode();
			FederalBankPayment bankPayment = new FederalBankPayment(FinacleWebServiceUtility.FINACLE_WEB_SERVICE_URL);
			ResponseVO responseVO = bankPayment.federalPayment(finPaymentVO.getRequestVO());
			if (responseVO != null) {
				AmFinacleLog finacleLog = OBProvider.getInstance().get(AmFinacleLog.class);
				Table table = OBDal.getInstance().get(Table.class, finPaymentVO.getTableId());
				Organization organization = OBDal.getInstance().get(Organization.class, finPaymentVO.getOrgId());
				finacleLog.setOrganization(organization);
				finacleLog.setTable(table);
				finacleLog.setRecordID(finPaymentVO.getRecordId());
				finacleLog.setTransactionDate(curDate);
				finacleLog.setAttempts(BigDecimal.ZERO);
				finacleLog.setTotalAttempts(new BigDecimal(FinacleWebServiceUtility.getTotalAttempts(finPaymentVO)));
				finacleLog.setSenderRef(finPaymentVO.getRequestVO().getSenderRefId());
				finacleLog.setRequestMsg(finPaymentVO.getRequestVO().getRequestBuffer().toString());
				if (responseVO.getResponseCode() != null) {
					if (responseVO.getResponseCode().equalsIgnoreCase(FinacleWebServiceUtility.FINACLE_SUCCESS_CODE)) {
						finacleLog.setTransStatus(FinacleWebServiceUtility.SUCCESS);
						message.setStatus(true);
						message.setMessage(responseVO.getSenderRefId());
					} else {
						finacleLog.setTransStatus(FinacleWebServiceUtility.FAILED);
						message.setStatus(false);
						message.setMessage(responseVO.getReason());
					}
					finacleLog.setTransaction(responseVO.getTransactionID());
					finacleLog.setResponseMsg(responseVO.getErrorMsg());
				} else {
					finacleLog.setTransStatus(FinacleWebServiceUtility.FAILED);
					finacleLog.setResponseMsg("Mandatory Fields are Missing!");
					ResponseVO responseVO2 = new ResponseVO();
					responseVO2.setErrorMsg("Mandatory Fields are Missing!");
					responseVO2.setSenderRefId(finPaymentVO.getRequestVO().getSenderRefId());
					finPaymentVO.setResponseVO(responseVO2);
					message.setStatus(false);
					message.setMessage(responseVO.getReason());
				}
				OBDal.getInstance().save(finacleLog);
				OBDal.getInstance().flush();
			} else {
				// For local testing.
				AmFinacleLog finacleLog = OBProvider.getInstance().get(AmFinacleLog.class);
				Table table = OBDal.getInstance().get(Table.class, finPaymentVO.getTableId());
				Organization organization = OBDal.getInstance().get(Organization.class, finPaymentVO.getOrgId());
				finacleLog.setOrganization(organization);
				finacleLog.setTable(table);
				finacleLog.setRecordID(finPaymentVO.getRecordId());
				finacleLog.setTransactionDate(curDate);
				finacleLog.setAttempts(BigDecimal.ZERO);
				finacleLog.setTotalAttempts(new BigDecimal(FinacleWebServiceUtility.getTotalAttempts(finPaymentVO)));
				finacleLog.setSenderRef(finPaymentVO.getRequestVO().getSenderRefId());
				finacleLog.setRequestMsg(finPaymentVO.getRequestVO().getRequestBuffer().toString());
				finacleLog.setTransStatus(FinacleWebServiceUtility.FAILED);
				finacleLog.setResponseMsg("Mandatory Fields are Missing!");
				ResponseVO responseVO2 = new ResponseVO();
				responseVO2.setErrorMsg("Mandatory Fields are Missing!");
				responseVO2.setSenderRefId(finPaymentVO.getRequestVO().getSenderRefId());
				finPaymentVO.setResponseVO(responseVO2);
				message.setStatus(false);
				message.setMessage("");
				OBDal.getInstance().save(finacleLog);
				OBDal.getInstance().flush();
			}
		} catch (Exception e) {
			sb.append("Exception occured while posting to finacle");
			e.printStackTrace();
		} finally {
			OBContext.restorePreviousMode();
		}
		message.setDescription(sb.toString());
		// OBContext.restorePreviousMode();
		return message;
	}

	public static ErrorMessage removeWorkOrder(String documentno) {
		ErrorMessage message = new ErrorMessage();
		try {
			OBContext.setAdminMode();
			final OBQuery<Order> obQuery = OBDal.getInstance().createQuery(Order.class,
					"prmWorkno='" + documentno + "' ");
			Order bpgs = obQuery.uniqueResult();
			if (bpgs != null) {
				int paymentcount = 0;
				int bomcount = 0;
				paymentcount = bpgs.getPRMPREMISESPAYMENTPRMCOrderIDList().size();
				/*
				 * for(PremisesPayment payment:bpgs.getPRMPREMISESPAYMENTPRMCOrderIDList()) {
				 * 
				 * }
				 */

				// No payments available
				if (CollectionUtils.isNotEmpty(bpgs.getOrderLineList())) {
					bomcount = bpgs.getOrderLineList().size();
				}

				// If no BOM No payment delete the Work order
				if (paymentcount == 0 && bomcount == 0) {
					// Delete all the history
					// Delete the work order history
					for (PrmWorkHistory history : bpgs.getPRMWORKHISTORYPRMCOrderIDList()) {
						log4j.debug("deleting history ...");
						OBDal.getInstance().remove(history);
					}
					OBDal.getInstance().flush();
					log4j.debug("deleting workorder ...");
					OBDal.getInstance().remove(bpgs);

				} else {
					message.setStatus(false);
					message.setMessage("No of payment available   " + paymentcount + " No of bom lines available ");
				}

			} else {
				message.setStatus(false);
				message.setMessage("Unable to find the record  " + documentno);

				return message;
			}

		} catch (NonUniqueResultException e) {
			message.setStatus(false);
			message.setMessage("Exception occured :" + e.getMessage());
			e.printStackTrace();

		} catch (Exception e) {
			e.printStackTrace();
			message.setStatus(false);
			message.setMessage("Exception occured :" + e.getMessage());
			message.setDescription(ExceptionUtils.getMessage(e.getCause()));
		} finally {
			OBContext.restorePreviousMode();
			OBDal.getInstance().commitAndClose();
		}

		return message;

	}

	public ErrorMessage reverseinvoicetransactionsWithOutTax(Invoice invoice) {
		ElementValue creditacct = SaleUtil.getFixedAssetAccountFromInvoice(invoice, ACCOUNTTYPE.PRODUCTEXPENSE);
		ErrorMessage msg = FinaclePosting(invoice.getOrganization().getId(), invoice.getSummedLineAmount().floatValue(),
				GSTPostingsToFinacle.getDefaultAccountSchema().getVendorLiability().getAccount().getFwAccountNo(),
				creditacct.getFwAccountNo(), "318", invoice.getId(), invoice.getOrganization().getAMSolId(),
				invoice.getOrganization().getAMSolId(), "REV", invoice.getDocumentNo());

		return msg;
	}

	public ErrorMessage reverseGRtransactionsWithOutTax(ShipmentInOut inout) {
		ElementValue creditacct = getFixedAssetAccountFromGR(inout, ACCOUNTTYPE.FIXEDASSET);
		ElementValue debitacct = getFixedAssetAccountFromGR(inout, ACCOUNTTYPE.PRODUCTEXPENSE);

		OBCriteria<AccountingFact> postingsCrt = OBDal.getInstance().createCriteria(AccountingFact.class);
		postingsCrt.add(Restrictions.eq(AccountingFact.PROPERTY_RECORDID, inout.getId()));
		List<AccountingFact> factlist = postingsCrt.list();
		if (CollectionUtils.isNotEmpty(factlist)) {
			float amount = 0.00f;
			for (AccountingFact acct : factlist) {
				amount = amount + acct.getCredit().floatValue();
			}

			ErrorMessage msg = FinaclePosting(inout.getOrganization().getId(), amount, debitacct.getFwAccountNo(),
					creditacct.getFwAccountNo(), "319", inout.getId(), inout.getOrganization().getAMSolId(),
					inout.getOrganization().getAMSolId(), "REV", inout.getDocumentNo());

			return msg;
		} else {
			ErrorMessage msg = new ErrorMessage();
			msg.setStatus(false);
			msg.setMessage("no data found in fact_acct table ");
			return msg;
		}
	}

	public ElementValue getFixedAssetAccountFromGR(ShipmentInOut inout, ACCOUNTTYPE accttype) {
		ElementValue elementvalue = new ElementValue();
		try {
			List<ShipmentInOutLine> inoutline = inout.getMaterialMgmtShipmentInOutLineList();
			if (!CollectionUtils.isEmpty(inoutline)) {
				AccountingCombination acct = new AccountingCombination();

				if (accttype == ACCOUNTTYPE.FIXEDASSET) {
					acct = inoutline.get(0).getProduct().getProductAccountsList().get(0).getFixedAsset();
				}
				if (accttype == ACCOUNTTYPE.REVENUE) {
					acct = inoutline.get(0).getProduct().getProductAccountsList().get(0).getProductRevenue();
				}
				if (accttype == ACCOUNTTYPE.PRODUCTEXPENSE) {
					acct = inoutline.get(0).getProduct().getProductAccountsList().get(0).getProductExpense();
				}

				if (acct != null) {
					elementvalue = acct.getAccount();
				}

			}

		} catch (Exception e) {
			e.printStackTrace();
		}
		return elementvalue;
	}

	public int updateInvoice(String documentno, TaxRate rate) {
		final OBQuery<Invoice> obQuery = OBDal.getInstance().createQuery(Invoice.class,
				"documentNo='" + documentno + "' ");
		Invoice invoice = obQuery.uniqueResult();
		List<Object> param = new ArrayList<Object>();
		param.add(invoice.getId());
		param.add(rate.getId());
		Query qry = OBDal.getInstance().getSession()
				.createSQLQuery("CALL AM_INVOICE_UPDATION(:P_INVOICE_ID,:P_TAX_ID)");
		qry.setParameter("P_INVOICE_ID", invoice.getId());
		qry.setParameter("P_TAX_ID", rate.getId());
		int result = qry.executeUpdate();
		log4j.info("result:" + result);
		OBDal.getInstance().commitAndClose();

		return result;
	}

	/**
	 * Pre-requisites
	 * 
	 * 1)Stop the acct server process for couple of minutes when you are executing
	 * the following script 2)Open purchase invoice window and select the particular
	 * record click on unpost. Description 3)AM_UPDATE_INVOICE will convert the
	 * invoice in to draft update the tax id for all the invoice lines.
	 * 4)Transaction which is already happened will be reversed 5)Re-calculation of
	 * assets will happen
	 *
	 * 
	 * @param documentno
	 * @param rate
	 * @return
	 */
	public ErrorMessage addTaxtotheInvoice(String documentno, TaxRate rate, REVERSALTYPE reversal) {
		ErrorMessage message = new ErrorMessage();
		try {
			OBContext.setAdminMode();
			if (documentno != null && !documentno.isEmpty()) {

				final OBQuery<Invoice> obQuery = OBDal.getInstance().createQuery(Invoice.class,
						"documentNo='" + documentno + "' ");
				Invoice invoice = obQuery.uniqueResult();
				if (invoice != null) {

					log4j.info("Docstatus:" + invoice.getDocumentStatus());
					if (invoice.getDocumentStatus().equals("CO")) {
						VariablesSecureApp vars = new VariablesSecureApp(invoice.getCreatedBy().getId(),
								invoice.getClient().getId(), invoice.getOrganization().getId(), "100", "en_US");
						Process process = OBDal.getInstance().get(Process.class, "111");
						@SuppressWarnings("unchecked")
						Map<String, String> parameters = new HashedMap();
						ProcessInstance pi = CallProcess.getInstance().callProcess(process, invoice.getId(),
								parameters);
						log4j.info("Result:" + pi.getResult());
						log4j.info("Error message:" + pi.getErrorMsg());
						log4j.info("Error message:" + pi.getId());

						if (pi.getResult() == 1) {

							if (ProcessPurchaseInvoice.isgstApplicable(invoice)) {

								String taxassetizationdate = "";
								DateFormat format = new SimpleDateFormat("dd-MM-yyyy");
								FinaclePostings pos = new FinaclePostings();
								taxassetizationdate = pos.getProperty("AM_ASSETIZATION_START_DATE");
								Date date1 = format.parse(taxassetizationdate);
								Date date2 = ProcessPurchaseInvoice.getMovementDateFromInvoice(invoice);
								ProcessPurchaseInvoice ppi = new ProcessPurchaseInvoice();

								if (date1.compareTo(date2) > 0) {
									GSTReAmortization.modifiedPurchaseInvoice(invoice);
								} else if (date1.compareTo(date2) <= 0) {
									ErrorMessage msg = ppi.reamortization(invoice);
									log4j.info(msg.isStatus() + ":" + msg.getDescription());
								}

							} else {
								ReAmortization amortization = new ReAmortization();
								amortization.modifiedPurchaseInvoice(invoice, process, vars);
							}
							ErrorMessage msg = checkReversal(invoice);
							if (msg.isStatus()) {
								if (reversal == REVERSALTYPE.GST) {
									// ErrorMessage trnmsg =
									// reverseinvoicetransactionsWithOutTax(invoice);
									// message = trnmsg;
								} else if (reversal == REVERSALTYPE.EXEMPT) {
									// Need to reverse all the gst transactions
									/*
									 * 1)Need to reverse the gst posted 2)Need to reverse the IPCR transations
									 * posted
									 **/
									/*
									 * OBCriteria<GSTPOSTINGS> gstpostings = OBDal.getInstance().createCriteria(
									 * GSTPOSTINGS.class); gstpostings.add(Restrictions.eq(
									 * GSTPOSTINGS.PROPERTY_INVOICE, invoice)); for (GSTPOSTINGS postings :
									 * gstpostings.list()) { String sourcesolid=""; String destinationsolid="";
									 * 
									 * ErrorMessage msg = UpdateScripts.FinaclePosting(invoice.
									 * getOrganization().getId(), invoice.getSummedLineAmount().floatValue( ),
									 * GSTPostingsToFinacle. getDefaultAccountSchema(). getVendorLiability().
									 * getAccount().getFwAccountNo(), creditacct.getFwAccountNo(), "318",
									 * invoice.getId(), invoice.getOrganization().getAMSolId(),
									 * invoice.getOrganization().getAMSolId(), "REV", invoice.getDocumentNo());
									 * log4j.info("msg:"+msg.isStatus()); log4j.info("msg:"+msg.getMessage());
									 * log4j.info("msg:"+msg.getDescription()); }
									 */
									message.setStatus(true);
									message.setMessage("Recalculation completed reversal is already completed");
								}
							} else {
								log4j.info("Error message:" + msg.getMessage() + " \n");
								message.setStatus(true);
								message.setMessage(
										"Recalculation completed reversal is already completed :" + msg.getMessage());
							}

						} else {
							message.setStatus(false);
							message.setMessage(pi.getErrorMsg());

						}

					} else {
						message.setStatus(false);
						message.setMessage("Document should be in completed status");
					}

				}
			} else {
				message.setStatus(false);
				message.setMessage("Invalid documentno");
			}
		} catch (NonUniqueResultException e) {
			message.setStatus(false);
			message.setMessage("Exception occured :" + e.getMessage());
			e.printStackTrace();

		} catch (Exception e) {
			message.setStatus(false);
			message.setMessage("Exception occured :" + e.getMessage());
			e.printStackTrace();
		} finally {
			OBContext.restorePreviousMode();
		}
		message.setDescription(message.getDescription());
		return message;
	}

	// Do it in future
	public ErrorMessage checkInvoicePrerequisites() {

		return null;
	}

	public ErrorMessage checkReversal(Invoice invoice) {

		ErrorMessage msg = new ErrorMessage();
		final OBQuery<AmFinacleLog> obQuery = OBDal.getInstance().createQuery(AmFinacleLog.class,
				"recordID='" + invoice.getId() + "' ");
		List<AmFinacleLog> bpgs = obQuery.list();
		if (CollectionUtils.isNotEmpty(bpgs)) {
			for (AmFinacleLog log : bpgs) {
				if (log.getSenderRef().contains("REV")) {
					msg.setStatus(false);
					msg.setMessage(log.getSenderRef());
				} else {
					msg.setStatus(true);
				}
			}
		}

		return msg;
	}

	public String recalculateassets(String documentno) throws ParseException {
		StringBuilder sb = new StringBuilder();
		final OBQuery<Invoice> obQuery = OBDal.getInstance().createQuery(Invoice.class,
				"documentNo='" + documentno + "' ");
		Invoice invoice = obQuery.uniqueResult();
		if (invoice != null) {
			if (ProcessPurchaseInvoice.isgstApplicable(invoice)) {

				String taxassetizationdate = "";
				DateFormat format = new SimpleDateFormat("dd-MM-yyyy");
				FinaclePostings pos = new FinaclePostings();
				taxassetizationdate = pos.getProperty("AM_ASSETIZATION_START_DATE");

				Date date1 = format.parse(taxassetizationdate);
				Date date2 = ProcessPurchaseInvoice.getMovementDateFromInvoice(invoice);
				ProcessPurchaseInvoice ppi = new ProcessPurchaseInvoice();

				if (date1.compareTo(date2) > 0) {
					GSTReAmortization.modifiedPurchaseInvoice(invoice);
				} else if (date1.compareTo(date2) <= 0) {
					ErrorMessage msg = ppi.reamortization(invoice);
					log4j.info(msg.isStatus() + ":" + msg.getDescription());
				}

			} else {
				VariablesSecureApp vars = new VariablesSecureApp(invoice.getCreatedBy().getId(),
						invoice.getClient().getId(), invoice.getOrganization().getId(), "100", "en_US");
				Process process = OBDal.getInstance().get(Process.class, "111");
				ReAmortization amortization = new ReAmortization();
				amortization.modifiedPurchaseInvoice(invoice, process, vars);
			}

		}
		return sb.toString();
	}

	public String premisesfailure(Invoice invoice, PremisesPayment ordPayment) {
		StringBuilder sb = new StringBuilder();
		String paymentID = "";
		if (invoice != null && ordPayment != null) {
			VariablesSecureApp vars = new VariablesSecureApp("100", invoice.getClient().getId(),
					invoice.getOrganization().getId(), "100", "en_US");

			// Setting the invoice ID into payment
			ordPayment.setPRMInvoice(invoice);
			OBDal.getInstance().save(ordPayment);
			OBDal.getInstance().flush();

			logln(sb, "After flushing invoice id:" + invoice.getId());
			// Process the payment, the amount below is not useful
			Invoice inv = invoice;
			BigDecimal paybleAmt = new BigDecimal("0");
			OBCriteria<InvoiceLine> invLineCrt = OBDal.getInstance().createCriteria(InvoiceLine.class);
			invLineCrt.add(Restrictions.eq(InvoiceLine.PROPERTY_INVOICE, inv));
			for (InvoiceLine invlines : invLineCrt.list()) {
				logln(sb, "INVOICE LINE EXIST FOR INVOICE");
				logln(sb, "Line net AMount :" + invlines.getLineNetAmount());
				paybleAmt = paybleAmt.add(invlines.getLineNetAmount());
			}
			logln(sb, "Amount forwadring to PAyment table : " + paybleAmt);
			PrmPayment prmPmt = new PrmPayment();

			// Pushing to Am payment and processing

			paymentID = prmPmt.createPayment(invoice.getId(), paybleAmt, ordPayment);
			logln(sb, "Before try block " + paymentID);
			try {
				if (!paymentID.isEmpty()) {
					AmPayment ampayment = OBDal.getInstance().get(AmPayment.class, paymentID);
					BigDecimal interimCompletedAmt = new BigDecimal(0);
					if (ordPayment.getPremiseInterimCompleted() != null) {
						interimCompletedAmt = ordPayment.getPremiseInterimCompleted();
					}
					ampayment.setPrmPremiseInterimAmount(interimCompletedAmt);
					OBDal.getInstance().save(ampayment);
				}

			} catch (Exception e) {
				e.printStackTrace();
			}
			try {
				logln(sb, "Calling the payment process in AM PAYMENT");

				OBCriteria<Process> processCrt = OBDal.getInstance().createCriteria(Process.class);
				processCrt.add(Restrictions.eq(Process.PROPERTY_SEARCHKEY, "CompletePayment"));
				Process process = (Process) processCrt.uniqueResult();
				ProcessCompletePayment completePayment = new ProcessCompletePayment();

				ProcessBundle bundlenew = new ProcessBundle(process.getId(), vars);
				Map<String, Object> paramsMapnew = null;
				paramsMapnew = new HashMap<String, Object>();
				paramsMapnew.put("AM_Payment_ID", paymentID);
				bundlenew.setParams(paramsMapnew);
				completePayment.doExecute(bundlenew);
				logln(sb, "END OF CREATE AM PAYMENT");

			} catch (Exception e) {
				e.printStackTrace();
			}

			try {
				UpdateOrderAndPayment updateOrd = new UpdateOrderAndPayment();
				String Notes = "Full Payment for amount : " + ordPayment.getPremisesPayment() + " " + "was approved by "
						+ OBContext.getOBContext().getRole().getName();
				String ordMsgs = updateOrd.updatePayAmounts(ordPayment, invoice.getSalesOrder(), Notes);
				if (ordMsgs.equals("Error")) {
					logln(sb, "Error happened while updating the order");
				} else {
					invoice.setAmInvoiceStatus("EM_INV_FWC");
					OBDal.getInstance().save(invoice);
					OBDal.getInstance().save(ordPayment.getPRMCOrder());
					OBDal.getInstance().flush();
					String updateDoc = "UPDATE PRM_WORK_BUDGET SET DOCSTATUS='DR' WHERE PRM_WORK_BUDGET_ID='"
							+ ordPayment.getPRMCOrder().getPrmWorkBudget().getId() + "'";
					OBDal.getInstance().getSession().createSQLQuery(updateDoc).executeUpdate();
					OBDal.getInstance().save(ordPayment);
					OBDal.getInstance().flush();
				}
			} catch (Exception e) {
				log4j.error("Error:" + e.getMessage());
				e.printStackTrace();
			}
			logln(sb, "Payment ID =" + paymentID);
			if (paymentID != null) {

				OBDal.getInstance().flush();
			}
		}
		return null;
	}

	public ErrorMessage recalculateinvoice(String documentno, TaxRate rate) {
		updateInvoice(documentno, rate);
		ErrorMessage message = addTaxtotheInvoice(documentno, rate, REVERSALTYPE.GST);
		log4j.info(message.isStatus());
		log4j.info(message.getMessage());
		log4j.info(message.getDescription());
		return message;

	}

	public boolean deleteunpostedlinesofasset(String a_asset_id) {
		String AMORTIZATION_LINE_NOT_PROCESS_HQL = "delete from org.openbravo.model.financialmgmt.assetmgmt.AmortizationLine e where  e.id in (select aml.id from org.openbravo.model.financialmgmt.assetmgmt.AmortizationLine aml,"
				+ "org.openbravo.model.financialmgmt.assetmgmt.Amortization am where aml.asset.id='" + a_asset_id
				+ "' and aml.amortization.id = am.id and am.processed = 'N')";
		try {
			int result = OBDal.getInstance().getSession().createQuery(AMORTIZATION_LINE_NOT_PROCESS_HQL)
					.executeUpdate();
			log4j.info("Deleted " + result + " Unprocessed AmortzationLines");
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
		return true;
	}

	public int getcountofunpostedlinesofasset(String a_asset_id) {
		String AMORTIZATION_LINE_NOT_PROCESS_HQL = "select count(e) from org.openbravo.model.financialmgmt.assetmgmt.AmortizationLine e where  e.id in (select aml.id from org.openbravo.model.financialmgmt.assetmgmt.AmortizationLine aml,"
				+ "org.openbravo.model.financialmgmt.assetmgmt.Amortization am where aml.asset.id='" + a_asset_id
				+ "' and aml.amortization.id = am.id and am.processed = 'Y')";
		try {
			Long result = (Long) OBDal.getInstance().getSession().createQuery(AMORTIZATION_LINE_NOT_PROCESS_HQL)
					.uniqueResult();
			log4j.info(result.intValue() + " Amortization Headers already Processed.");
			return result.intValue();
		} catch (Exception e) {
			e.printStackTrace();
			return 0;
		}
	}

	public void recalInvoiceDoneInNextQuarter(List<String> assets) {
		for (String asset : assets) {
			log4j.info("Recalculating - " + asset);
			// Need to delete all the unposted lines of an asset
			deleteunpostedlinesofasset(asset);
			SessionHandler.getInstance().commitAndStart();
			// AM_DEPCORRINVQTR(p_asset_id IN VARCHAR2)
			Query qry = OBDal.getInstance().getSession().createSQLQuery("CALL AM_DEPCORRINVQTR(:p_asset_id)");
			qry.setParameter("p_asset_id", asset);
			int result = qry.executeUpdate();
			log4j.info(result);
		}
	}

	public StringBuilder logln(StringBuilder msg, String message) {
		msg.append(message);
		msg.append("\n");
		return msg;

	}

	public enum REVERSALTYPE {
		GST, EXEMPT
	}

	/**
	 * @param assetid
	 *            Please change the dates accordingly
	 */
	public void correctnedepreciation(String assetid) {
		// Change the dates accordingly
		/*
		 * String Q4 = "March 31, 2019"; String Q2 = "June 30, 2019"; DateTimeFormatter
		 * formatter = DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.ENGLISH);
		 * LocalDate Q4date = LocalDate.parse(Q4, formatter); LocalDate Q2date =
		 * LocalDate.parse(Q2, formatter); System.out.println(Q4date); // 2010-01-02
		 * System.out.println(Q2date);
		 */
		try {
			if (StringUtils.isNotEmpty(assetid)) {
				Asset asset = OBDal.getInstance().get(Asset.class, assetid);
				final OBQuery<AmortizationLine> q4query = OBDal.getInstance().createQuery(AmortizationLine.class,
						"asset.id='" + asset.getId() + "' and amDateacct='31-MAR-2019'");
				final OBQuery<AmortizationLine> q2query = OBDal.getInstance().createQuery(AmortizationLine.class,
						"asset.id='" + asset.getId() + "' and amDateacct='30-JUN-2019'");
				List<AmortizationLine> q4list = q4query.list();
				List<AmortizationLine> q2list = q2query.list();
				if (CollectionUtils.isNotEmpty(q4list) && CollectionUtils.isNotEmpty(q2list)) {
					AmortizationLine l1 = q4list.get(0);
					AmortizationLine l2 = q2list.get(0);
					BigDecimal l2amt = l2.getAmortizationAmount().multiply(new BigDecimal("-1"));

					BigDecimal l1amt = l1.getAmortizationAmount().subtract(l2amt.multiply(new BigDecimal(2)));

					BigDecimal l2bv = l1.getAMBookValue().subtract(l1amt);
					log4j.info(asset.getName() + "," + asset.getResidualAssetValue() + "," + l1amt + "," + l2amt + ","
							+ l2bv);

					l1.setAmortizationAmount(l1amt);
					l2.setAmortizationAmount(l2amt);
					l2.setAMBookValue(l2bv);
					OBDal.getInstance().save(l1);
					OBDal.getInstance().save(l2);
				} else {
					log4j.info(asset.getName() + " is not having all the requred lines");
				}

			} else {
				return;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static void pushEntrysToFactAcct(String disposalId) {
		AmAssetDisposal disp = OBDal.getInstance().get(AmAssetDisposal.class, disposalId);

		double amttemp = 0.0d;
		System.out.println("Size of the disposal line:" + disp.getAMASSETDISPOSALLINEList().size());
		for (AmAssetDisposalLine displine : disp.getAMASSETDISPOSALLINEList()) {
			System.out.println("Line Status:" + displine.getStatus());
			if (displine.getStatus().equals("H_AM_AP")) {
				String HQL_QUERY = "select aml from org.openbravo.model.financialmgmt.assetmgmt.AmortizationLine aml,org.openbravo.model.financialmgmt.assetmgmt.Amortization am where aml.asset.id='"
						+ displine.getAsset().getId()
						+ "' and aml.amortization.id = am.id  ORDER BY am.accountingDate desc";
				@SuppressWarnings("unchecked")
				List<AmortizationLine> amlist = OBDal.getInstance().getSession().createQuery(HQL_QUERY).list();
				/*
				 * Cases handling here 1)Asset with zero line called as fully depreciated 2)All
				 * other cases we are calculating the book value and we are posting
				 */
				if (amlist.size() > 0) {

					amttemp = amttemp + (amlist.get(0).getAMBookValue().doubleValue()
							- amlist.get(0).getAmortizationAmount().doubleValue());
					System.out.println("Size is greaterthan zero");
					System.out.println("Book value:" + amlist.get(0).getAMBookValue().doubleValue());
					System.out.println("Broken Day Amount:" + amlist.get(0).getAmortizationAmount().doubleValue());
				} else {
					// Calculate the residual value
					double residualval = 0.0d;
					String deptype = "";
					double depvalue = 0.0d;
					try {
						OBContext.setAdminMode();
						deptype = displine.getAsset().getProduct().getAmAssetType().getAmdepreciationtype().toString();
						depvalue = displine.getAsset().getProduct().getAmAssetType().getAmdepreciationtypevalue()
								.doubleValue();
						if (deptype.equalsIgnoreCase("VAL")) {
							residualval = depvalue;
							amttemp = amttemp + residualval;
						} else {
							double addcost = 0.0;
							double credittaken = 0.0;
							double assetval = 0.0;
							if (displine.getAsset().getIlastAdditionalCost() != null) {
								addcost = displine.getAsset().getIlastAdditionalCost().doubleValue();
							}
							if (displine.getAsset().getIlastCreditTaken() != null) {
								credittaken = displine.getAsset().getIlastCreditTaken().doubleValue();
							}
							assetval = ((displine.getAsset().getIlastPurchaseCost().doubleValue() + addcost)
									- credittaken);
							residualval = assetval * (depvalue / 100);
							System.out.println("final residualval:" + residualval);
							amttemp = amttemp + residualval;
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
		System.out.println("amount:" + amount);

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
			System.out.println("current period is : " + currentPeriod);
			System.out.println("GETTING PERIOD SUCCESSFULLY");

			GLBatch glbatch = OBProvider.getInstance().get(GLBatch.class);
			// GLBatch glbatch1=new GLBatch();
			final OBCriteria<GLBatch> glbatchList = OBDal.getInstance().createCriteria(GLBatch.class);
			glbatchList.add(Restrictions.eq(GLBatch.PROPERTY_ORGANIZATION, disp.getOrganization()));
			glbatchList.add(Restrictions.eq(GLBatch.PROPERTY_PERIOD, currentPeriod));
			// glbatchList.list();

			System.out.println("Getting the list of glbatch");
			final OBCriteria<GLCategory> glCatList = OBDal.getInstance().createCriteria(GLCategory.class);
			glCatList.add(Restrictions.eq(GLCategory.PROPERTY_NAME, "Standard"));

			GLCategory glCat = glCatList.list().get(0);

			if (CollectionUtils.isNotEmpty(glbatchList.list())) {
				// Get the first Object
				glbatch = glbatchList.list().get(0);
				System.out.println("Found GL Batch :" + glbatchList.list().size());
			} else {
				// Creating a new glbatch

				try {

					glbatch.setAccountingDate(java.sql.Date.valueOf(LocalDate.now()));
					glbatch.setPeriod(currentPeriod);

					System.out.println("Organization : " + disp.getOrganization().getName());
					glbatch.setOrganization(disp.getOrganization());
					glbatch.setDescription(disp.getOrganization().getName() + " Journal " + firstDateOfMonth.getTime());

					glbatch.setCurrency(OBContext.getOBContext().getCurrentClient().getCurrency());
					glbatch.setProcessed(false);

					String strDocumentno = Utility.getDocumentNo(new DalConnectionProvider(),
							OBContext.getOBContext().getCurrentClient().getId(), "GL_JournalBatch", true);
					glbatch.setDocumentNo(strDocumentno);
					glbatch.setDocumentDate(curDate);
					glbatch.setGLCategory(glCat);
					System.out.println("glbatch.getDescription():" + glbatch.getDescription() + "getClient:"
							+ glbatch.getClient() + "getCreatedBy():" + glbatch.getCreatedBy() + "getUpdatedBy:"
							+ glbatch.getUpdatedBy() + "getOrganization:" + glbatch.getOrganization() + "desc:"
							+ glbatch.getDescription() + "Posttype:" + glbatch.getPostingType() + "");
					OBDal.getInstance().save(glbatch);
					OBDal.getInstance().flush();
				} catch (Exception e) {
					e.printStackTrace();
				}

			} // New glbatch creation ended here
			GLJournal journalHeader = OBProvider.getInstance().get(GLJournal.class);

			journalHeader.setAccountingDate(curDate);
			journalHeader.setOrganization(disp.getOrganization());
			journalHeader.setPeriod(currentPeriod);
			final OBCriteria<AcctSchema> acctSchemaList = OBDal.getInstance().createCriteria(AcctSchema.class);
			acctSchemaList.add(Restrictions.eq(AcctSchema.PROPERTY_NAME, "Federal Bank"));

			journalHeader.setAccountingSchema(acctSchemaList.list().get(0));
			journalHeader.setDescription(disp.getId());
			journalHeader.setCurrency(OBContext.getOBContext().getCurrentClient().getCurrency());
			journalHeader.setDocumentDate(curDate);
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
			glJournalLineDebit.setAccountingDate(curDate);
			glJournalLineDebit.setLineNo(new Long(10));
			glJournalLineDebit.setCurrency(OBContext.getOBContext().getCurrentClient().getCurrency());
			// The below two lines should change
			glJournalLineDebit.setDebit(amount);
			glJournalLineDebit.setForeignCurrencyDebit(amount);
			// Up to here
			glJournalLineDebit.setForeignCurrencyCredit(new BigDecimal(0));
			glJournalLineDebit.setCredit(new BigDecimal(0));
			glJournalLineDebit.setDescription("Postings for Write Off" + disp.getDocumentNo());
			// I need to change this accounting part it should be as mentioned
			// in the
			// document
			String validcomb_sqldr = "select cv.c_validcombination_id from c_elementvalue ce inner join C_ValidCombination cv on cv.account_id=ce.c_elementvalue_id where ce.em_fw_account_no='0011541001' and rownum<2";
			String accid = (String) OBDal.getInstance().getSession().createSQLQuery(validcomb_sqldr).uniqueResult();
			System.out.println("accid :" + accid);
			AccountingCombination deditbAcc = OBDal.getInstance().get(AccountingCombination.class, accid);

			glJournalLineDebit.setAccountingCombination(deditbAcc);
			glJournalLineDebit.setJournalEntry(journalHeader);
			OBDal.getInstance().save(glJournalLineDebit);

			GLJournalLine glJournalLineCredit = OBProvider.getInstance().get(GLJournalLine.class);
			glJournalLineCredit.setOrganization(disp.getOrganization());
			glJournalLineCredit.setAccountingDate(curDate);
			glJournalLineCredit.setLineNo(new Long(20));
			glJournalLineCredit.setCurrency(OBContext.getOBContext().getCurrentClient().getCurrency());
			glJournalLineCredit.setCredit(amount);
			glJournalLineCredit.setDebit(new BigDecimal(0));
			glJournalLineCredit.setForeignCurrencyCredit(amount);
			glJournalLineCredit.setForeignCurrencyDebit(new BigDecimal(0));
			glJournalLineCredit.setDescription("Write Off Document No:" + disp.getDocumentNo());
			// Need to change according the requirement
			String validcomb_sqlcr = "select cv.c_validcombination_id from c_elementvalue ce inner join C_ValidCombination cv on cv.account_id=ce.c_elementvalue_id where ce.em_fw_account_no='0091030001' and rownum<2";

			String accidcr = (String) OBDal.getInstance().getSession().createSQLQuery(validcomb_sqlcr).uniqueResult();
			System.out.println("accidcr :" + accidcr);
			AccountingCombination creditAcc = OBDal.getInstance().get(AccountingCombination.class, accidcr);
			glJournalLineCredit.setAccountingCombination(creditAcc);
			glJournalLineCredit.setJournalEntry(journalHeader);

			journalLines.add(glJournalLineCredit);
			OBDal.getInstance().save(glJournalLineCredit);
			OBDal.getInstance().flush();

			System.out.println(
					"glJournalLineCredit " + glJournalLineCredit + " ,glJournalLineDebit " + glJournalLineDebit);

			/*
			 * try { // Call GL_Journal_Post method from the database. final List<Object>
			 * parameters = new ArrayList<Object>(); parameters.add(null);
			 * parameters.add(journalHeader.getId()); final String procedureName =
			 * "gl_journal_post"; CallStoredProcedure mm =
			 * CallStoredProcedure.getInstance(); mm.call(procedureName, parameters, null,
			 * false, false); } catch (Exception e) {
			 * OBDal.getInstance().rollbackAndClose(); OBError error =
			 * OBMessageUtils.translateError(conn, vars, vars.getLanguage(), e.getCause()
			 * .getMessage()); throw new OBException(error.getMessage());
			 * e.printStackTrace(); }
			 */

			/*
			 * ProcessBundle pb = new ProcessBundle("5BE14AA10165490A9ADEFB7532F7FA94",
			 * vars); HashMap<String, Object> parameters = new HashMap<String, Object>();
			 * parameters.put("GL_Journal_ID", journalHeader.getId());
			 * pb.setParams(parameters);
			 * 
			 * System.out.println("Before executing FIN_AddPaymentFromJournal");
			 * FIN_AddPaymentFromJournal finProcess = new FIN_AddPaymentFromJournal();
			 * finProcess.execute(pb);
			 */

			// Change it to different doctype
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			OBContext.restorePreviousMode();
		}

	}

	/**
	 * Checks if invoice for this shipment is available If no invoice is available
	 * Then it will check how many posted lines are available If more than one is
	 * there it will throw an error If all the above validations are successful
	 * 1)System will delete the un-posted amortization lines 2)Deletes all the
	 * assets belongs to shipment 3)Voids the PO 4)update the shipment as in-active
	 * so no invoice creation won't be available in future
	 * 
	 * @param inout
	 * @return
	 */
	public ErrorMessage deactivateGR(ShipmentInOut inout) {
		// TODO Try to use "Return to vendor shipment/Return to vendor in
		// future"
		ErrorMessage msg = validateShipment(inout);
		if (msg.isStatus()) {
			for (ShipmentInOutLine line : inout.getMaterialMgmtShipmentInOutLineList()) {
				OBCriteria<Asset> astCrt = OBDal.getInstance().createCriteria(Asset.class);
				astCrt.add(Restrictions.eq(Asset.PROPERTY_AMMINOUTLINE, line));
				List<Asset> assets = astCrt.list();
				for (Asset asset : assets) {
					boolean status = deleteunpostedlinesofasset(asset.getId());
					if (status) {
						log4j.info("Removed lines for " + asset.getSearchKey());
					}
				}
				for (Asset asset : assets) {
					log4j.info("Removed asset:" + asset.getSearchKey());
					OBDal.getInstance().remove(asset);
				}
				inout.setActive(false);
				OBDal.getInstance().save(inout);

				Order order = inout.getSalesOrder();
				order.setDocumentStatus("VO");
				OBDal.getInstance().save(order);
				msg.setStatus(true);
				msg.setMessage("Deleted assets successfully");
				return msg;
			}
		} else {
			return msg;
		}
		return null;
	}

	public ErrorMessage validateShipment(ShipmentInOut inout) {
		ErrorMessage msg = new ErrorMessage();
		StringBuilder builder = new StringBuilder();
		AtomicInteger countint = new AtomicInteger();
		// Checks if invoice is available
		OBCriteria<Invoice> invCrt = OBDal.getInstance().createCriteria(Invoice.class);
		invCrt.add(Restrictions.eq(Invoice.PROPERTY_SALESORDER, inout.getSalesOrder()));
		List<Invoice> invlist = invCrt.list();
		if (CollectionUtils.isEmpty(invlist)) {
			for (ShipmentInOutLine line : inout.getMaterialMgmtShipmentInOutLineList()) {
				OBCriteria<Asset> astCrt = OBDal.getInstance().createCriteria(Asset.class);
				astCrt.add(Restrictions.eq(Asset.PROPERTY_AMMINOUTLINE, line));
				List<Asset> assets = astCrt.list();
				for (Asset asset : assets) {
					int count = getcountofunpostedlinesofasset(asset.getId());
					if (count > 0) {
						builder.append(asset.getSearchKey() + ": some depreciation lines are already posted");
						countint.incrementAndGet();
						break;
					}
				}
				if (countint.get() > 0) {
					msg.setStatus(false);
					msg.setMessage(builder.toString());
				} else {
					msg.setStatus(true);
					msg.setMessage("Validations are successful");
				}
			}
		} else {
			msg.setStatus(false);
			msg.setMessage("Invoice is already available for the Shipment");
		}
		return msg;
	}

	public static String getAccountName(String id) {
		String accountName = "";
		OBCriteria<ElementValue> elementCrt = OBDal.getInstance().createCriteria(ElementValue.class);
		elementCrt.add(Restrictions.eq(ElementValue.PROPERTY_FWACCOUNTNO, id.trim()));
		List<ElementValue> elmtlist = elementCrt.list();
		if (CollectionUtils.isNotEmpty(elmtlist)) {
			log4j.info("Getting Account Name:" + elmtlist.get(0).getName());
			accountName = elmtlist.get(0).getName();
		} else {
			accountName = "Not available in the masters";
		}
		return accountName;
	}

}
