package com.splenta.admin.ad_process.bulkprocesses;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.log4j.Logger;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.financialmgmt.assetmgmt.Asset;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

import com.splenta.admin.BulkProcess;

public class AssetValidations {
	private static final Logger log = Logger.getLogger(AssetValidations.class);

	/**
	 * @author satyamera108
	 * @param val
	 *            Asset Code
	 * @return Asset, if existed
	 */
	public Asset getAsset(String val) {
		try {
			final OBCriteria<Asset> assetList = OBDal.getInstance().createCriteria(Asset.class);
			assetList.add(Restrictions.eq(Asset.PROPERTY_SEARCHKEY, val));
			return assetList.list().get(0);
		} catch (IndexOutOfBoundsException e) {
			log.info("No asset found with Value " + val);
			return null;
		}
	}

	/**
	 * @author satyamera108
	 * @param asset
	 *            Asset
	 * @return True if Asset is Purchased with GST
	 */
	public Boolean isGSTAsset(Asset asset) {
		return asset.getAcctvalueamt() != null ? true : false;
	}

	/**
	 * @author satyamera108
	 * @param org
	 *            Source Organization
	 * @param asset
	 *            Asset
	 * @return True if Asset existed in Source Organization, False otherwise
	 */
	public Boolean checkBranch(Organization org, Asset asset) {
		try {
			return (asset.getOrganization().equals(org));
		} catch (NullPointerException e) {
			log.info("Asset not belongs to Branch. NullPointerException");
			return false;
		} catch (IndexOutOfBoundsException e) {
			log.info("Asset not belongs to Branch. IndexOutOfBoundsException");
			return false;
		}
	}

	/**
	 * @author satyamera108
	 * @param asset
	 *            Asset
	 * @param txn
	 *            Sales/Purchase Transaction
	 * @return List of Pending Sales/ Purchase Invoice
	 */

	@SuppressWarnings("unchecked")
	public List<String> checkInvoice(Asset asset, Boolean txn) {
		String checkPendInv;
		List<String> pendInv = new ArrayList<String>();
		List<String> pendInvList = new ArrayList<String>();
		if (txn) {
			checkPendInv = "select e.invoice.documentNo from InvoiceLine e "
					+ "where e.invoice.salesTransaction=true and e.invoice.documentStatus ='CO' " + "and e.asset.id = '"
					+ asset.getId() + "'";
			pendInvList = OBDal.getInstance().getSession().createQuery(checkPendInv).list();
			if (pendInvList.isEmpty()) {
				final OBCriteria<OrderLine> orderLineList = OBDal.getInstance().createCriteria(OrderLine.class);
				orderLineList.add(Restrictions.eq(OrderLine.PROPERTY_ASSET, asset));
				for (OrderLine ordLine : orderLineList.list()) {
					final OBCriteria<Order> orderList = OBDal.getInstance().createCriteria(Order.class);
					orderList.add(Restrictions.eq(Order.PROPERTY_ID, ordLine.getSalesOrder().getId()));
					orderList.add(Restrictions.eq(Order.PROPERTY_DOCUMENTSTATUS, "CO"));
					if (!orderList.list().isEmpty()) {
						pendInv.add(orderList.list().get(0).getDocumentNo());
						log.info("Pending Sales Invoice.");
					} else {
						log.info("Check for the SOs.");
					}
				}
			} else {
				log.info("No Pending Sales Invoice.");
			}
		} else {
			if (asset.getAmMInoutline() == null) {
				log.info("No Pending Purchase Invoice.");
			} else {
				checkPendInv = "select e.invoice.documentNo from InvoiceLine e "
						+ "where e.invoice.salesTransaction=false and e.invoice.documentStatus ='CO' "
						+ "and e.goodsShipmentLine.id = '" + asset.getAmMInoutline().getId() + "'";
				pendInvList = OBDal.getInstance().getSession().createQuery(checkPendInv).list();
				if (pendInvList.isEmpty()) {
					log.info("Pending Purchase Invoice.");
					pendInv.add(asset.getAmMInoutline().getShipmentReceipt().getDocumentNo());
				} else {
					log.info("No Pending Purchase Invoice.");
				}
			}
		}
		return pendInv;
	}

	/**
	 * @author satyamera108
	 * @param sourceOrg
	 *            Source Organization
	 * @param txn
	 *            Sales/Purchase Transaction
	 * @param asset
	 *            Asset
	 * @return List of Pending Sales/ Purchase Order
	 */

	public List<String> checkOrder(Organization sourceOrg, Boolean txn, Asset asset) {
		List<String> pendOrder = new ArrayList<String>();
		try {
			sourceOrg = (sourceOrg == null) ? asset.getOrganization() : sourceOrg;
			final OBCriteria<Order> orderList = OBDal.getInstance().createCriteria(Order.class);
			orderList.add(Restrictions.eq(Order.PROPERTY_ORGANIZATION, sourceOrg));
			orderList.add(Restrictions.eq(Order.PROPERTY_DOCUMENTSTATUS, "DR"));
			orderList.add(Restrictions.eq(Order.PROPERTY_SALESTRANSACTION, txn));
			if (!txn) {
				orderList.add(Restrictions.eq(Order.PROPERTY_PRMISPREMISE, false));
				for (Order order : orderList.list()) {
					final OBCriteria<ShipmentInOut> shipList = OBDal.getInstance().createCriteria(ShipmentInOut.class);
					shipList.add(Restrictions.eq(ShipmentInOut.PROPERTY_SALESORDER, order));
					if (shipList.list().isEmpty()) {
						pendOrder.add(order.getDocumentNo());
					}
				}
				log.info((pendOrder.isEmpty() ? "No Pending Purchase Orders."
						: "Pending Purchase Orders: " + pendOrder.size()));
			} else {
				for (Order order : orderList.list()) {
					final OBCriteria<OrderLine> orderLineList = OBDal.getInstance().createCriteria(OrderLine.class);
					orderLineList.add(Restrictions.eq(OrderLine.PROPERTY_SALESORDER, order));
					orderLineList.add(Restrictions.eq(OrderLine.PROPERTY_ASSET, asset));
					if (!orderLineList.list().isEmpty()) {
						pendOrder.add(order.getDocumentNo());
					}
				}
				log.info((pendOrder.isEmpty() ? "No Pending Sales Orders."
						: "Pending Sales Orders: " + pendOrder.size()));
			}
		} catch (NullPointerException e) {
			log.warn("No Pending Sale/Purchase Orders" + e);
		}
		return pendOrder;
	}

	/**
	 * @author satyamera108
	 * @param asset
	 *            Asset
	 * @return List of Pending Disposal Request
	 */

	@SuppressWarnings("unchecked")
	public List<String> checkDisposal(Asset asset) {
		List<String> pendReq = new ArrayList<String>();
		List<String> pendReqList = new ArrayList<String>();
		final String CheckPendingDisp = "select e.assetDisposal.documentNo from AM_ASSET_DISPOSAL_LINE "
				+ "e where e.asset.id = '" + asset.getId()
				+ "' and (e.lineStatus in ('DIS_RC','DIS_PE') or e.lineStatus is null)";
		pendReqList = OBDal.getInstance().getSession().createQuery(CheckPendingDisp).list();
		if (pendReqList.isEmpty()) {
			log.info("No Pending Disposal Requests.");
		} else {
			log.info("Pending Disposal Requests.");
			pendReq = pendReqList;
		}
		return pendReq;
	}

	/**
	 * @author satyamera108
	 * @param asset
	 *            Asset
	 * @return List of Pending Transfer Request
	 */
	@SuppressWarnings("unchecked")
	public List<String> checkTransfer(Asset asset) {
		List<String> pendReq = new ArrayList<String>();
		List<String> pendReqList = new ArrayList<String>();
		final String CheckPendingDisp = "select e.assetTransferline.assetTransfer.documentNo from AM_ASSET_TRANSFER_SUBLINE e "
				+ "where e.lineTransferStatus='TR_PE' and e.asset.id = '" + asset.getId() + "'";
		pendReqList = OBDal.getInstance().getSession().createQuery(CheckPendingDisp).list();
		if (pendReqList.isEmpty()) {
			log.info("No Pending Transfer Requests.");
		} else {
			log.info("Pending Transfer Requests.");
			pendReq = pendReqList;
		}
		return pendReq;
	}

	/**
	 * @author satyamera108
	 * @param orgID
	 *            Organization
	 * @return Region of the Organization
	 */
	public String getRegion(String orgID) {
		final String hql = "select e.locationAddress.region.name from OrganizationInformation e where e.organization.id =  '"
				+ orgID + "'";
		final Session session = OBDal.getInstance().getSession();
		final Query query = session.createQuery(hql);
		final String reg = (String) query.uniqueResult();
		return reg;
	}

	/**
	 * @author satyamera108
	 * @param asset
	 *            Asset
	 * @return True if asset Disposed
	 */
	public Boolean isDisposed(Asset asset) {
		return (asset.isDisposed()) ? true : false;
	}

	/**
	 * @author satyamera108
	 * @param asset
	 *            Asset
	 * @return True if asset Transferred
	 */
	public Boolean isTransferred(Asset asset) {
		return (asset.isAmIstransfered() == true && asset.getAmBookvalue() == BigDecimal.ZERO) ? true : false;
	}

	/**
	 * @author satyamera108
	 * @param asset
	 *            Asset
	 * @return True if asset belongs to IT group, False otherwise
	 */
	public String isIT(Asset asset) {
		return (asset.getAssetCategory().getName().equals("COMPP")) ? "IT"
				: (asset.getAssetCategory().getName().matches("BLDNG|LAND")) ? "PRM" : "NonIT";
	}

	/**
	 * @author satyamera108
	 * @param asset
	 *            Asset
	 * @return Asset Type
	 */
	public String getAssetType(Asset asset) {
		return (asset.getProduct().getAmAssetType().getCommercialName());
	}

	public OBError validateAttachment(BulkProcess rec) {
		OBContext.setAdminMode();
		log.info("Checking for Valid Attachment . . .");
		OBError error = new OBError();
		List<Attachment> attach = getAttachment(rec.getId());
		if (rec.getDocType().equals("BTR")) {
			log.info("Selected Bulk Transfer. No Attachment needed");
			if (CollectionUtils.isNotEmpty(attach)) {
				for (int p = 0; p < attach.size(); p++) {
					if (attach.get(p).getName().contains("FailedValidation")) {
						log.info("Deleting Attachment");
						OBDal.getInstance().getSession().delete(attach.get(p));
					}
					OBDal.getInstance().flush();
				}
			}
			error.setType("Success");
		} else {
			if (CollectionUtils.isNotEmpty(attach)) {
				for (int p = 0; p < attach.size(); p++) {
					if (attach.get(p).getName().contains("FailedValidation")) {
						log.info("Deleting Attachment");
						OBDal.getInstance().getSession().delete(attach.get(p));
					}
					OBDal.getInstance().flush();
				}

				String ext = FilenameUtils.getExtension(attach.get(0).getName());
				if (attach.size() > 1) {
					error.setType("Error");
					error.setTitle("Found multiple attachments.");
					error.setMessage("Only ONE Attachemnt is allowed.");
				} else {
					if (ext.equals("csv")) {
						log.info("DocAction > " + rec.getDocAction());
						if (rec.getDocAction().matches("FW|AP|NA")) {
							if (attach.get(0).getSequenceNumber() == 108) {
								error.setType("Success");
								log.info("Validated Attachment Found");
							} else {
								error.setType("Error");
								error.setTitle("Validated Attachment not found.");
								error.setMessage("Please Reject the request.");
							}
						} else {
							error.setType("Success");
						}
					} else {
						error.setType("Error");
						error.setTitle("Invalid attachment");
						error.setMessage("Please attach csv file.");
					}
				}
			} else {
				if (rec.getDocAction().matches("AP")) {
					error.setType("Error");
					error.setTitle("Validated Attachment not found.");
					error.setMessage("Please Reject the request.");
				} else {
					error.setType("Error");
					error.setTitle("Attachment not found.");
					error.setMessage("Please attach csv file.");
				}
			}
		}
		OBContext.restorePreviousMode();
		return error;
	}

	/**
	 * Checks whether Attachment Available Based On ReqId
	 * 
	 * @author Vikas
	 * @param reqid
	 * @return Attachment list
	 */
	public List<Attachment> getAttachment(String reqid) {
		OBContext.setAdminMode();
		List<Attachment> attachments = new ArrayList<Attachment>();
		try {
			OBCriteria<Attachment> attachCrt = OBDal.getInstance().createCriteria(Attachment.class);
			attachCrt.add(Restrictions.eq(Attachment.PROPERTY_RECORD, reqid));
			List<Attachment> attachmentstmp = attachCrt.list();
			if (CollectionUtils.isNotEmpty(attachmentstmp)) {
				attachments = attachmentstmp;
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			OBContext.restorePreviousMode();
		}
		return attachments;
	}

	/**
	 * @author satya_splenta
	 * @param data
	 *            Bulk Process Request
	 * @return Success if Accounting date is within allowed range
	 */
	public OBError dateAcctCheck(BulkProcess data) {
		OBError error = new OBError();
		LocalDate dateAcct = data.getAcctDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
		LocalDate today = LocalDate.now(), qtrStart = getQuarterBeginDate(today), qtrEnd = getQuarterEndDate(today);
		Boolean dateCheck = ((qtrStart.compareTo(dateAcct) <= 0) && (today.compareTo(dateAcct) <= 0)
				&& (qtrEnd.compareTo(dateAcct) >= 0)) ? true : false;
		if (dateCheck) {
			error.setType("Success");
		} else {
			error.setType("error");
			error.setTitle("Invalid Accounting Date.");
			error.setMessage("Accounting Date must be "
					+ ((qtrStart.compareTo(dateAcct) > 0) ? "\n" + "on/after " + qtrStart.format(formatter)
							: (today.compareTo(dateAcct) > 0) ? "\n" + "on/after " + today.format(formatter) + "\n"
									: "on/before " + qtrEnd.format(formatter)));
		}
		return error;
	}

	/**
	 * @author satyamera108
	 * @param currentday
	 *            Current Date
	 * @return Quarter start date
	 */
	static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy").withLocale(Locale.ENGLISH);

	public LocalDate getQuarterBeginDate(LocalDate currentday) {
		int curMonth = currentday.getMonthValue();
		String qDate = "01-"
				+ ((curMonth <= 3) ? "01"
						: (curMonth > 3 && curMonth <= 6) ? "04" : (curMonth > 6 && curMonth <= 9) ? "07" : "10")
				+ "-" + currentday.getYear();
		LocalDate qtrStart = LocalDate.parse(qDate, formatter);
		return qtrStart;
	}

	/**
	 * @author satyamera108
	 * @param currentday
	 *            Current Date
	 * @return Quarter end date
	 */
	public LocalDate getQuarterEndDate(LocalDate currentday) {
		int curMonth = currentday.getMonthValue();
		String qDate = ((curMonth <= 3) ? "31-03"
				: (curMonth > 3 && curMonth <= 6) ? "30-06" : (curMonth > 6 && curMonth <= 9) ? "30-09" : "31-12") + "-"
				+ currentday.getYear();
		LocalDate qrtEnd = LocalDate.parse(qDate, formatter);
		return qrtEnd;
	}

}