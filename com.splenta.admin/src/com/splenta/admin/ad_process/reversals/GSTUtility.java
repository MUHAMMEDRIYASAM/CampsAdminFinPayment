package com.splenta.admin.ad_process.reversals;

import org.apache.log4j.Logger;
import org.openbravo.model.financialmgmt.accounting.coa.ElementValue;

import com.chimera.finaclewebservice.ad_process.FinacleWebServiceUtility;
import com.chimera.finaclewebservice.ws.main.FederalBankPayment;
import com.chimera.fixedassetmanagement.ad_process.SaleUtil;
import com.chimera.fixedassetmanagement.ad_process.SaleUtil.ACCOUNTTYPE;
import com.splenta.gst.GSTPOSTINGS;
import com.splenta.pns.ad_process.GSTPostingsToFinacle;

public class GSTUtility {

	private final static Logger log4j = Logger.getLogger(GSTUtility.class);

	// Replace all the account numbers from element code
	private final static String CGSTACCT = "0088510005";
	private final static String SGSTACCT = "0088510004";
	private final static String IGSTACCT = "0088510006";
	private final static String UGSTACCT = "0088510007";

	// Replace all the account numbers from element code
	/*
	 * private final static String GCGSTACCT = "0088510019"; private final static
	 * String GSGSTACCT = "0088510018"; private final static String GIGSTACCT =
	 * "0088510016";
	 */

	private final static String CG_CGSTACCT = "0088510019";
	private final static String CG_SGSTACCT = "0088510018";
	private final static String CG_IGSTACCT = "0088510016";

	private final static String HOCGSTACCT = "0088510274";
	private final static String HOSGSTACCT = "0088510273";
	private final static String HOIGSTACCT = "0088510275";
	private final static String HOUGSTACCT = "0088510007";

	private final static String ITCGSTACCT = "0088510289";
	private final static String ITSGSTACCT = "0088510288";
	private final static String ITIGSTACCT = "0088510290";
	private final static String ITUGSTACCT = "0088510007";

	public Account getSolid(GSTPOSTINGS gst) {
		
		// TODO Handle marketing cases in future
		Account acct = new Account();
		boolean isDept = FinacleWebServiceUtility.isOrgDepartment(gst.getOrganization().getId());
		/* Sold id of the record is decided here */
		if (isDept) {
			String solid = FederalBankPayment.properties.getProperty(FinacleWebServiceUtility.PROP_DEPT_SOLID);
			if (gst.getInvoice().isSalesTransaction()) {
				acct.setRemsol(solid);
				acct.setBensol(gst.getOrganization().getAMSolId());
			} else if (gst.getInvoice().isPasIspnsinvoice() || gst.getInvoice().isOpxIsopex()
					|| gst.getInvoice().isExpIsexpense()) {
				acct.setRemsol(solid);
				acct.setBensol(solid);
			} else {// Fixed asset related transactions
				if (gst.isGl()) {
					acct.setRemsol(solid);
					acct.setBensol(GSTPostingsToFinacle.getNodalSolId(gst.getOrganization()));
				} else {
					acct.setRemsol(GSTPostingsToFinacle.getNodalSolId(gst.getOrganization()));
					acct.setBensol(solid);
				}
			}

		} else {
			if (gst.getInvoice().isSalesTransaction()) {
				acct.setBensol(gst.getOrganization().getOrganizationInformationList().get(0).getLocationAddress()
						.getRegion().getGstNodalsol());
				acct.setRemsol(gst.getOrganization().getAMSolId());
			} else {
				if (gst.getInvoice().isExpIsexpense() == false) {
					acct.setRemsol(gst.getOrganization().getAMSolId());
					acct.setBensol(gst.getOrganization().getAMSolId());
				}
			}

		}

		return acct;

	}

	public Account getaccountNumber(GSTPOSTINGS gst) {
		FederalBankPayment.initload();
		Account account = new Account();
		boolean isDept = FinacleWebServiceUtility.isOrgDepartment(gst.getOrganization().getId());
		String gstdrnumber = "";
		if (gst.isGl()) {
			try {
				if (gst.getInvoice().isPasIspnsinvoice() == false) {
					ElementValue debitacct = SaleUtil.getFixedAssetAccountFromInvoice(gst.getInvoice(),
							ACCOUNTTYPE.FIXEDASSET);
					log4j.info("gst.isGl(): " + gst.isGl() + "" + gst.getGSTType().toString());
					ElementValue creditacct = GSTPostingsToFinacle.getTaxAccountSchema(gst);
					if (debitacct != null && creditacct != null) {
						account.setDebitacct(debitacct.getFwAccountNo());
						account.setCreditacct(creditacct.getFwAccountNo());
						Account sol = getSolid(gst);
						account.setBensol(sol.getBensol());
						account.setRemsol(sol.getRemsol());
						return account;
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else {
			if (isDept) {
				log4j.info("GST Department posting " + gst.getOrganization().getSearchKey());
				//TODO Implement marketing in future 
				if (gst.getInvoice().isPasIspnsinvoice() || gst.getInvoice().isOpxIsopex()) {
					if (gst.getOrganization().getSearchKey().equals("CD")) {
						if (gst.getGSTType().contains("IGST")) {
							gstdrnumber = ITIGSTACCT;
						} else if (gst.getGSTType().contains("SGST")) {
							gstdrnumber = ITSGSTACCT;
						} else if (gst.getGSTType().contains("CGST")) {
							gstdrnumber = ITCGSTACCT;
						} else if (gst.getGSTType().contains("UGST")) {
							gstdrnumber = ITUGSTACCT;
						}
					} else if (gst.getOrganization().getSearchKey().equals("CO")) {
						/* Keep a condition like CSD */
						if (gst.getGSTType().contains("IGST")) {
							gstdrnumber = HOIGSTACCT;
						} else if (gst.getGSTType().contains("SGST")) {
							gstdrnumber = HOSGSTACCT;
						} else if (gst.getGSTType().contains("CGST")) {
							gstdrnumber = HOCGSTACCT;
						} else if (gst.getGSTType().contains("UGST")) {
							gstdrnumber = HOUGSTACCT;
						}
					} else {
						/*
						 * As per the requirement from sreejith sir
						 */
						if (gst.getGSTType().contains("IGST")) {
							gstdrnumber = ITIGSTACCT;
						} else if (gst.getGSTType().contains("SGST")) {
							gstdrnumber = ITSGSTACCT;
						} else if (gst.getGSTType().contains("CGST")) {
							gstdrnumber = ITCGSTACCT;
						} else if (gst.getGSTType().contains("UGST")) {
							gstdrnumber = ITUGSTACCT;
						}
					}
				} else {
					/*
					 * For fixed asset related transactions account numbers should be different
					 */
					if (gst.getGSTType().contains("IGST")) {
						gstdrnumber = CG_IGSTACCT;
					} else if (gst.getGSTType().contains("SGST")) {
						gstdrnumber = CG_SGSTACCT;
					} else if (gst.getGSTType().contains("CGST")) {
						gstdrnumber = CG_CGSTACCT;
					} else if (gst.getGSTType().contains("UGST")) {
						gstdrnumber = CG_CGSTACCT;
					}

				}

			} else {

				if (gst.getInvoice().isPasIspnsinvoice() || gst.getInvoice().isOpxIsopex()) {
					if (gst.getGSTType().contains("IGST")) {
						gstdrnumber = IGSTACCT;
					} else if (gst.getGSTType().contains("SGST")) {
						gstdrnumber = SGSTACCT;
					} else if (gst.getGSTType().contains("CGST")) {
						gstdrnumber = CGSTACCT;
					} else if (gst.getGSTType().contains("UGST")) {
						gstdrnumber = UGSTACCT;
					}
				} else {
					if (gst.getGSTType().contains("IGST")) {
						gstdrnumber = CG_IGSTACCT;
					} else if (gst.getGSTType().contains("SGST")) {
						gstdrnumber = CG_SGSTACCT;
					} else if (gst.getGSTType().contains("CGST")) {
						gstdrnumber = CG_CGSTACCT;
					} else if (gst.getGSTType().contains("UGST")) {
						gstdrnumber = CG_CGSTACCT;
					}
				}

			}

			log4j.info("GSTDEBIT NUMBER:" + gstdrnumber);
			account.setDebitacct(gstdrnumber);
			account.setCreditacct(GSTPostingsToFinacle.getDefaultAccountSchema().getVendorLiability().getAccount()
					.getFwAccountNo().toString());
			Account sol = getSolid(gst);
			account.setBensol(sol.getBensol());
			account.setRemsol(sol.getRemsol());
			return account;
		}

		return null;
	}

	public class Account {
		public String creditacct;
		public String debitacct;
		public String remsol;
		public String bensol;

		public String getRemsol() {
			return remsol;
		}

		public void setRemsol(String remsol) {
			this.remsol = remsol;
		}

		public String getBensol() {
			return bensol;
		}

		public void setBensol(String bensol) {
			this.bensol = bensol;
		}

		public String getCreditacct() {
			return creditacct;
		}

		public void setCreditacct(String creditacct) {
			this.creditacct = creditacct;
		}

		public String getDebitacct() {
			return debitacct;
		}

		public void setDebitacct(String debitacct) {
			this.debitacct = debitacct;
		}

	}

}
