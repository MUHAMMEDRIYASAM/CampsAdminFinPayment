package com.splenta.admin.ad_eventHandler;

import java.math.BigDecimal;
import javax.enterprise.event.Observes;

import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.client.kernel.event.EntityDeleteEvent;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.financialmgmt.assetmgmt.Asset;

import com.splenta.admin.BulkProcess;
import com.splenta.admin.ad_process.bulkprocesses.AssetValidations;

public class DataCaptureHandler extends EntityPersistenceEventObserver {
	private static Entity[] entities = { ModelProvider.getInstance().getEntity(BulkProcess.ENTITY_NAME) };
	AssetValidations Validate = WeldUtils.getInstanceFromStaticBeanManager(AssetValidations.class);
	final String newLine = System.getProperty("line.separator");

	@Override
	protected Entity[] getObservedEntities() {
		return entities;
	}

	public void onUpdate(@Observes EntityUpdateEvent event) {
		if (!isValidEvent(event)) {
			return;
		}

		try {
			OBContext.setAdminMode();
			final BulkProcess data = (BulkProcess) event.getTargetInstance();
			String status = data.getDocStatus() != null ? data.getDocStatus() : "DR";
			if (status.equals("DR")) {
				OBError error = checkMandarotyFields(data);
				if (error.getType().equals("warn")) {
					throw new OBException(error.getMessage());
				}

			}
		} finally {
			OBContext.restorePreviousMode();
		}
	}

	public void onSave(@Observes EntityNewEvent event) {
		if (!isValidEvent(event)) {
			return;
		}
		try {
			OBError error = new OBError();
			OBContext.setAdminMode();
			final BulkProcess data = (BulkProcess) event.getTargetInstance();
			String status = data.getDocStatus() != null ? data.getDocStatus() : "DR";
			try {
				final OBCriteria<BulkProcess> pendReq = OBDal.getInstance().createCriteria(BulkProcess.class);
				pendReq.add(Restrictions.eq(BulkProcess.PROPERTY_PROCESSED, false));
				throw new OBException("Process the request > " + pendReq.list().get(0).getDocNumber());
			} catch (IndexOutOfBoundsException e) {
				if (status.equals("DR")) {
					error = checkMandarotyFields(data);
					if (error.getType().equals("warn")) {
						throw new OBException(error.getTitle() + "\n" + error.getMessage());
					}
				}
			}
		} finally {
			OBContext.restorePreviousMode();
		}
	}

	public void onDelete(@Observes EntityDeleteEvent event) {
		if (!isValidEvent(event)) {
			return;
		}
		final BulkProcess data = (BulkProcess) event.getTargetInstance();
		if (!data.getDocStatus().matches("DR|RJD|VLD")) {
			throw new OBException("Deletion is not allowed.");
		}
	}

	public OBError checkMandarotyFields(BulkProcess data) {
		OBError dateCheck = Validate.dateAcctCheck(data);
		OBError error = new OBError();
		StringBuilder err = new StringBuilder();
		OBCriteria<Asset> astlst = OBDal.getInstance().createCriteria(Asset.class);
		astlst.add(Restrictions.eq(Asset.PROPERTY_ORGANIZATION, data.getSrcOrg()));
		// TODO Confirm the following logic from Sunil sir.
		// We cannot dispose Transferred Asset, Cannot transfer Disposed Asset.
		// Common condition BookValue>0
		astlst.add(Restrictions.gt(Asset.PROPERTY_AMBOOKVALUE, new BigDecimal(0)));
		if (!dateCheck.getType().equals("Success")) {
			error.setType("warn");
			error.setTitle(dateCheck.getTitle());
			error.setMessage(dateCheck.getMessage());
		} else if (!data.getDocType().equals("WO")) {
			if (astlst.list().isEmpty()) {
				error.setType("warn");
				err.append("Assets are not available.");
			} else {
				if (data.getDstnOrg() == null) {
					error.setType("warn");
					err.append("Please select Destination Branch.");
				} else {
					Boolean regCheck = Validate.getRegion(data.getSrcOrg().getId())
							.equals(Validate.getRegion(data.getDstnOrg().getId()));
					if (!regCheck) {
						error.setType("warn");
						err.append("Both the Branches must be in same state.");
					}
				}
			}
			error.setMessage(err.toString() + "\n");
		} else {
			OBCriteria<Asset> staticAstList = OBDal.getInstance().createCriteria(Asset.class);
			staticAstList.add(Restrictions.eq(Asset.PROPERTY_STATIC, true));
			if (staticAstList.list().size() > 0) {
				error.setType("warn");
				err.append("Following assets are marked for WriteOff: \n");
				for (Asset ast : staticAstList.list()) {
					err.append(ast.getSearchKey() + " (" + ast.getOrganization().getName() + ") | ");
				}
			} else if (!data.getSrcOrg().getId().equals("0") && astlst.list().isEmpty()) {
				error.setType("warn");
				err.append("Assets are not available. \n");
			}
			error.setMessage(err.toString());
		}
		return error;
	}
}