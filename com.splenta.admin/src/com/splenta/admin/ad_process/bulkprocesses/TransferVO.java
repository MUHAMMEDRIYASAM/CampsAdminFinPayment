/**
 * 
 */
package com.splenta.admin.ad_process.bulkprocesses;

import java.util.List;

import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.assetmgmt.Asset;

/**
 * @author vikas_splenta
 *
 */
public class TransferVO {
	private Organization srcOrg;
	private Organization dstnOrg;
	private List<Asset> assets;

	public Organization getSource_organization() {
		return srcOrg;
	}

	public void setSource_organization(Organization srcOrg) {
		this.srcOrg = srcOrg;
	}

	public Organization getDestination_organization() {
		return dstnOrg;
	}

	public void setDestination_organization(Organization dstnOrg) {
		this.dstnOrg = dstnOrg;
	}

	public List<Asset> getAssets() {
		return assets;
	}

	public void setAssets(List<Asset> assets) {
		this.assets = assets;
	}
}