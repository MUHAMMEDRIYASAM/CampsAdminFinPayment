package com.splenta.admin.ad_process.depreciation;

import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.TemporalAdjusters;
import java.util.Date;
import java.util.List;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.financialmgmt.assetmgmt.AmortizationLine;
import org.openbravo.model.financialmgmt.assetmgmt.Asset;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.service.db.DalBaseProcess;

public class DepreciationCorrection extends DalBaseProcess {

	private final static Logger log4j = Logger.getLogger(DepreciationCorrection.class);

	@Override
	protected void doExecute(ProcessBundle bundle) throws Exception {
		// TODO Auto-generated method stub
		log4j.info("Entering DepreciationCorrection Process");
		String current_quater_date = "";
		String next_quater_date = "";
		String is_quaterly_process = "";

		is_quaterly_process = getPreferenceValue("AM_ISQUARTERLYPROCESS");
		if (is_quaterly_process == "Y" || is_quaterly_process.equals("Y")) {
			bundle.getLogger().logln("AM_ISQUARTERLYPROCESS ==" + is_quaterly_process);
			try {

				current_quater_date = getPreferenceValue("AM_POSTING_DATE");
				bundle.getLogger().logln("AM_POSTING_DATE ==" + current_quater_date);
				DateTimeFormatter formatter = new DateTimeFormatterBuilder().parseCaseInsensitive()
						.appendPattern("yyyy-MM-dd").toFormatter();

				LocalDate end = LocalDate.parse(current_quater_date, formatter).plusMonths(3)
						.with(TemporalAdjusters.lastDayOfMonth());
				next_quater_date = end.format(formatter);

				List<String> assets = getAssests(current_quater_date, next_quater_date);

				if (assets != null && assets.size() > 0) {
					log4j.info("### assetList=" + assets.size() + " ###");

					for (int i = 0; i < assets.size(); i++) {
						bundle.getLogger().logln(assets.get(i));
						correctnedepreciation(assets.get(i), current_quater_date, next_quater_date);
					}

				} else {
					log4j.info("### assetList is empty!!! ###");
					bundle.getLogger().logln("No assets Found !");
				}

			} catch (Exception e) {
				e.printStackTrace();
			}
		} else {
			bundle.getLogger().logln("AM_ISQUARTERLYPROCESS ==" + is_quaterly_process);
		}
	}

	@SuppressWarnings("unchecked")
	private List<String> getAssests(String current_quater_date, String next_quater_date) {

		List<String> assetList = null;
		try {

			assetList = OBDal.getInstance().getSession()
					.createSQLQuery("select ast.A_ASSET_ID from a_asset ast "
							+ "inner join A_AMORTIZATIONLINE aml on aml.A_ASSET_ID= ast.A_ASSET_ID "
							+ "where ast.a_asset_id in (" + "SELECT ast.A_ASSET_ID FROM a_amortization am "
							+ "INNER JOIN a_amortizationline aml ON aml.a_amortization_id = am.a_amortization_id "
							+ "INNER JOIN a_asset ast ON ast.a_asset_id= aml.a_asset_id "
							+ "inner join ad_org adorg on adorg.ad_org_id=ast.AD_ORG_ID "
							+ "WHERE am.dateacct=to_date('" + current_quater_date + "','YYYY-MM-DD') "
							+ "AND ((aml.em_am_book_value-aml.amortizationamt)-ast.residualassetvalueamt)<0) "
							+ "and aml.EM_AM_DATEACCT=to_date('" + next_quater_date
							+ "', 'YYYY-MM-DD') and aml.AMORTIZATIONAMT<0")
					.list();
		} catch (Exception e) {
			log4j.info("Exception in ASSET Query\n" + e);
		}

		return assetList;

	}

	public void correctnedepreciation(String assetid, String current_quater_date, String next_quater_date)
			throws ParseException {
		// Change the dates accordingly
		/*
		 * String Q4 = "March 31, 2019"; String Q2 = "June 30, 2019"; DateTimeFormatter
		 * formatter = DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.ENGLISH);
		 * LocalDate Q4date = LocalDate.parse(Q4, formatter); LocalDate Q2date =
		 * LocalDate.parse(Q2, formatter); System.out.println(Q4date); // 2010-01-02
		 * System.out.println(Q2date);
		 */
		System.out.println("###########" + assetid);
		SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd");
		SimpleDateFormat outputFormat = new SimpleDateFormat("dd-MMM-yyyy");
		Date thisDate = inputFormat.parse(current_quater_date);
		Date nextDate = inputFormat.parse(next_quater_date);
		current_quater_date = outputFormat.format(thisDate);
		next_quater_date = outputFormat.format(nextDate);
		try {

			if (StringUtils.isNotEmpty(assetid)) {
				Asset asset = OBDal.getInstance().get(Asset.class, assetid);
				final OBQuery<AmortizationLine> q4query = OBDal.getInstance().createQuery(AmortizationLine.class,
						"asset.id='" + asset.getId() + "' and amDateacct='" + current_quater_date + "'");

				final OBQuery<AmortizationLine> q2query = OBDal.getInstance().createQuery(AmortizationLine.class,
						"asset.id='" + asset.getId() + "' and amDateacct='" + next_quater_date + "'");

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

					/*
					 * log4j.info(q4list.get(0).getAMBookValue() + ":" +
					 * q2list.get(0).getAMBookValue());
					 * log4j.info(q4list.get(0).getAmortizationAmount() + ":" +
					 * q2list.get(0).getAmortizationAmount());
					 * log4j.info("New dep l1 amount:"+l1amt+": old amount:"+q4list.get(0).
					 * getAmortizationAmount() );
					 * log4j.info("New dep l2 amount:"+l2amt+": old amount:"+q4list.get(0).
					 * getAmortizationAmount() );
					 * log4j.info("New BV l2 amount:"+l2bv+": old amount:"+q2list.get(0).
					 * getAMBookValue());
					 */

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

	public String getPreferenceValue(String keyName) {
		String response = "Failed";
		try {
			OBCriteria<Preference> messageCrt = OBDal.getInstance().createCriteria(Preference.class);
			messageCrt.add(Restrictions.eq(Preference.PROPERTY_ATTRIBUTE, keyName));
			List<Preference> messagelist = messageCrt.list();
			if (!CollectionUtils.isEmpty(messagelist)) {
				response = messagelist.get(0).getSearchKey();

			}
		} catch (Exception e) {
			e.printStackTrace();
			return response;
		}
		return response;

	}
}
