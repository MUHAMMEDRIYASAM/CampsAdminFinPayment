package com.splenta.admin.resources;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.openbravo.client.kernel.BaseComponentProvider;
import org.openbravo.client.kernel.Component;

public class ApplicationUIProvider extends BaseComponentProvider {

	@Override
	public Component getComponent(String componentId, Map<String, Object> parameters) {
		return null;
	}

	public List<ComponentResource> getGlobalComponentResources() {
		final List<ComponentResource> globalResources = new ArrayList<ComponentResource>();
		globalResources.add(createStaticResource("web/com.splenta.admin/js/ob-ap-utilities.js", true));
		return globalResources;
	}

}
