package com.splenta.admin.ad_callouts;

import javax.servlet.ServletException;

import org.apache.commons.lang.StringUtils;
import org.openbravo.erpCommon.ad_callouts.SimpleCallout;

public class UpdateValues extends SimpleCallout {

	@Override
	protected void execute(CalloutInfo info) throws ServletException {
		String strChanged = info.getLastFieldChanged();
		if (StringUtils.equals(strChanged, "inpdoctype")) {
			info.addResult("inpsrcorg", null);
			info.addResult("inpdstnorg", null);
			info.addResult("inpdateacct", null);
			info.addResult("inpinternalnotes", null);
		} else if (StringUtils.equals(strChanged, "inpcRegionId")) {
			info.addResult("inpsrcorg", null);
			info.addResult("inpdstnorg", null);
		}
	}
}