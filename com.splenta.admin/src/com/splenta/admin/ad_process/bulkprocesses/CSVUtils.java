package com.splenta.admin.ad_process.bulkprocesses;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.apache.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.financialmgmt.assetmgmt.Asset;

import com.chimera.fixedassetmanagement.ad_process.ErrorMessage;
import com.google.common.base.Stopwatch;
import com.splenta.admin.BulkProcess;

public class CSVUtils {
	private static final Logger log = Logger.getLogger(CSVUtils.class);

	private static final char DEFAULT_SEPARATOR = ',';
	private static final char DEFAULT_QUOTE = '"';
	private static final int FILE_LENGTH = 1;
	AssetValidations validate = WeldUtils.getInstanceFromStaticBeanManager(AssetValidations.class);

	@SuppressWarnings("unused")
	private List<String> parseLine(String cvsLine) {
		return parseLine(cvsLine, DEFAULT_SEPARATOR, DEFAULT_QUOTE);
	}

	private List<String> parseLine(String cvsLine, char separators) {
		return parseLine(cvsLine, separators, DEFAULT_QUOTE);
	}

	private List<String> parseLine(String cvsLine, char separators, char customQuote) {
		List<String> result = new ArrayList<>();
		// if empty, return!
		if (cvsLine == null) {
			return result;
		}
		if (cvsLine.isEmpty()) {
			return result;
		}
		if (customQuote == ' ') {
			customQuote = DEFAULT_QUOTE;
		}
		if (separators == ' ') {
			separators = DEFAULT_SEPARATOR;
		}
		StringBuffer curVal = new StringBuffer();
		boolean inQuotes = false;
		boolean startCollectChar = false;
		boolean doubleQuotesInColumn = false;
		char[] chars = cvsLine.toCharArray();
		for (char ch : chars) {
			if (inQuotes) {
				startCollectChar = true;
				if (ch == customQuote) {
					inQuotes = false;
					doubleQuotesInColumn = false;
				} else {
					// Fixed : allow "" in custom quote enclosed
					if (ch == '\"') {
						if (!doubleQuotesInColumn) {
							curVal.append(ch);
							doubleQuotesInColumn = true;
						}
					} else {
						curVal.append(ch);
					}
				}
			} else {
				if (ch == customQuote) {
					inQuotes = true;
					// Fixed : allow "" in empty quote enclosed
					if (chars[0] != '"' && customQuote == '\"') {
						curVal.append('"');
					}
					// double quotes in column will hit this!
					if (startCollectChar) {
						curVal.append('"');
					}
				} else if (ch == separators) {
					result.add(curVal.toString());
					curVal = new StringBuffer();
					startCollectChar = false;
				} else if (ch == '\r') {
					// ignore LF characters
					continue;
				} else if (ch == '\n') {
					// the end, break!
					break;
				} else {
					curVal.append(ch);
				}
			}
		}
		result.add(curVal.toString());
		return result;
	}

	public ErrorMessage checkAllFileChecks(Path FILENAME) throws CSVFileReaderExceptions {
		ErrorMessage message = new ErrorMessage();
		StringBuffer sb = new StringBuffer();
		AtomicInteger valid_count = new AtomicInteger();
		AtomicInteger line_count = new AtomicInteger();
		AtomicInteger errors = new AtomicInteger();
		AtomicInteger count = new AtomicInteger();
		HashMap<Integer, String> header = new HashMap<Integer, String>();
		try (Stream<String> stream = Files.lines(FILENAME)) {
			stream.forEach(sCurrentLine -> {
				System.out.println(sCurrentLine);
				List<String> resp = parseLine(sCurrentLine, DEFAULT_SEPARATOR);
				AtomicInteger size = new AtomicInteger();
				for (String result : resp) {
					if (count.get() == 0) {
						System.out.println("Header :" + result);
						header.put(size.get(), result);

						if (header.get(size.get()).equals("AssetValue")) {
							valid_count.incrementAndGet();
						} else {
							sb.append("" + result + " is invalid.");
						}

					} else {
						line_count.incrementAndGet();
						if (resp.size() != FILE_LENGTH) {
							errors.incrementAndGet();
							sb.append("Expected number of Columns: " + FILE_LENGTH + ". Uploaded file contained "
									+ resp.size() + " at line " + count);
							sb.append('\n');
						}
						break;
						// Check the length of each column here
					}
					size.incrementAndGet();
				}
				count.incrementAndGet();
				line_count.incrementAndGet();
			});
			if (valid_count.get() > FILE_LENGTH) {
				message.setStatus(false);
				message.setMessage("Invalid file format.");
				message.setDescription("More headers are available in the csv file than expected. " + sb.toString());
				return message;
			}
			if (valid_count.get() < FILE_LENGTH) {
				message.setStatus(false);
				message.setMessage("Invalid file format.");
				message.setDescription("Required headers are not available.");
				return message;
			}
			if (errors.get() > 0) {
				message.setStatus(false);
				message.setMessage("Check the following errors.");
				message.setDescription(sb.toString() + System.lineSeparator() + errors + ",");
				return message;
			}
			if (valid_count.get() == FILE_LENGTH) {
				message.setStatus(true);
				log.info("File format is fine. Check the Validations.");
				return message;
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		return message;
	}

	/**
	 * @author satyamera108
	 * @param FILENAME
	 * @param bulkRecord
	 * @return status whether all the Validations are success
	 * @throws CSVFileReaderExceptions
	 * @throws OBException
	 */
	// TODO Know the reason why?
	List<String> prInv, dsInv, dispDoc, tranDoc, prOrd, dsOrd;

	public ErrorMessage readFile(Path FILENAME, BulkProcess bulkRecord) throws CSVFileReaderExceptions, OBException {
		log.info("Validation Start . . .");
		Stopwatch timer = Stopwatch.createStarted();
		ErrorMessage message = new ErrorMessage();
		StringBuilder errorMessage = new StringBuilder();
		prInv = dsInv = dispDoc = tranDoc = prOrd = dsOrd = null;

		if (bulkRecord.getDocType().equals("BTR")) {
			final OBCriteria<Asset> assetList = OBDal.getInstance().createCriteria(Asset.class);
			assetList.add(Restrictions.eq(Asset.PROPERTY_ORGANIZATION, bulkRecord.getSrcOrg()));
			log.info("No. of Assets selected: " + assetList.list().size());
			prOrd = validate.checkOrder(bulkRecord.getSrcOrg(), false, null);
			errorMessage.append(prOrd.isEmpty() ? "" : "" + "," + "Pending Purchase Orders," + LS(prOrd));

			for (Asset asset : assetList.list()) {
				String value = asset.getSearchKey();
				log.info("Validating - " + value);
				dsOrd = validate.checkOrder(bulkRecord.getSrcOrg(), true, asset);
				prInv = validate.checkInvoice(asset, false);
				dsInv = validate.checkInvoice(asset, true);
				dispDoc = validate.checkDisposal(asset);
				tranDoc = validate.checkTransfer(asset);

				errorMessage.append(dsOrd.isEmpty() ? "" : value + "," + "Pending Sales Order," + LS(dsOrd));
				errorMessage.append(prInv.isEmpty() ? "" : value + "," + "Pending Purchase Invoice," + LS(prInv));
				errorMessage.append(dsInv.isEmpty() ? "" : value + "," + "Pending Sales Invoice," + LS(dsInv));
				errorMessage.append(dispDoc.isEmpty() ? "" : value + "," + "Pending Diposal," + LS(dispDoc));
				errorMessage.append(tranDoc.isEmpty() ? "" : value + "," + "Pending Transfer," + LS(tranDoc));
			}
			if (errorMessage.length() > 0) {
				message.setStatus(false);
				message.setMessage("Validations failed.");
				message.setDescription(
						"Please find the FailedValidations_" + bulkRecord.getDocNumber() + " in Attachments");
				generateErrorFile(FILENAME, "AssetValue,Error Message,DocNo." + "\n" + errorMessage.toString(),
						bulkRecord);
			} else {
				assetList.add(Restrictions.gt(Asset.PROPERTY_RESIDUALASSETVALUE, BigDecimal.valueOf(0.00)));
				int assetCount = 0;
				double purchaseCost = 0, bookValue = 0;
				for (Asset ast : assetList.list()) {
					ast.setHelpComment(bulkRecord.getDocNumber());
					ast.setAmIsdisprocessing(true);
					assetCount++;
					purchaseCost = purchaseCost + ast.getAssetValue().doubleValue();
					bookValue = bookValue + ast.getAmBookvalue().doubleValue();
				}
				log.info("assetCount>" + assetCount + " purchaseCost>" + purchaseCost + " bookValue>" + bookValue);
				bulkRecord.setAssetCount(BigDecimal.valueOf(assetCount));
				bulkRecord.setPurchaseCost(BigDecimal.valueOf(purchaseCost));
				bulkRecord.setBookValue(BigDecimal.valueOf(bookValue));
				OBDal.getInstance().save(bulkRecord);
				OBDal.getInstance().flush();
				log.info("Passed all the validations. Marked " + assetList.list().size() + " Assets.");
				message.setStatus(true);
			}
			log.info("Time taken: " + timer.stop());
			return message;

		} else {
			AtomicInteger count = new AtomicInteger();
			ConcurrentHashMap<Integer, String> header = new ConcurrentHashMap<Integer, String>();
			log.info("FILENAME --> " + FILENAME);
			// -------------

			String line = null;
			HashSet<String> lines = new HashSet<>();
			HashSet<String> mixedMinions = new HashSet<>();
			List<String> astList = new ArrayList<>();
			HashSet<String> dupValues = new HashSet<>();

			try (BufferedReader br = new BufferedReader(new FileReader(FILENAME.toString()))) {
				br.readLine();
				while ((line = br.readLine()) != null) {
					if (lines.add(line)) {
						astList.add(line);
					} else {
						dupValues.add(line);
					}
				}
				br.close();
			} catch (IOException e) {
				log.warn("Error in Duplicatation Code" + e);
			}

			dupValues.remove("");
			int rowCount = 0;
			try {
				OBQuery<Preference> pref = OBDal.getInstance().createQuery(Preference.class, "attribute='ROWCOUNT'");
				rowCount = Integer.valueOf((pref.uniqueResult().getSearchKey()));
			} catch (NumberFormatException e) {
				log.info("Set the ROWCOUNT.");
			}
			if (astList.size() > rowCount) {
				message.setStatus(false);
				message.setMessage("Validations failed.");
				message.setDescription((rowCount == 0) ? "Invalid ROWCOUNT. Please contact CAMPS Admin."
						: "File has more than " + rowCount + " records.");
			} else if (!dupValues.isEmpty()) {
				message.setStatus(false);
				message.setMessage("Validations failed.");
				message.setDescription("Duplicate Asset Values found. \n" + dupValues);
			} else if (astList.isEmpty()) {
				message.setStatus(false);
				message.setMessage("Validations failed.");
				message.setDescription("Empty Asset values.");
			}

			else {
				try (Stream<String> stream = Files.lines(FILENAME)) {
					stream.forEach(sCurrentLine -> {
						// To avoid blank line import from csv
						if (!sCurrentLine.equals(",") && !sCurrentLine.isEmpty() && sCurrentLine.length() != 0) {
							int size = 0;
							List<String> resp = parseLine(sCurrentLine, DEFAULT_SEPARATOR);
							if (resp.size() != FILE_LENGTH) {
								new OBException("Actual values length should be " + FILE_LENGTH + " supplied length in "
										+ (size + 1) + " column is " + resp.size());
							}
							for (String value : resp) {
								if (count.get() == 0) {
									header.put(size, value);
									log.info("Header >> " + value);
								} else {
									if (header.get(size).equals("AssetValue")) {
										if (value != null) {
											Asset asset = validate.getAsset(value);
											if (asset != null) {
												log.info("Validating - " + value);
												Boolean brachCheck = (bulkRecord.getSrcOrg().getId().contentEquals("0"))
														? true
														: validate.checkBranch(bulkRecord.getSrcOrg(), asset);

												mixedMinions.add(
														(bulkRecord.getDocType().equals("WO")) ? validate.isIT(asset)
																: validate.getAssetType(asset));

												if (brachCheck) {
													prInv = validate.checkInvoice(asset, false);
													dsInv = validate.checkInvoice(asset, true);
													dispDoc = validate.checkDisposal(asset);
													tranDoc = validate.checkTransfer(asset);
													dsOrd = validate.checkOrder(null, true, asset);
													Boolean isDisposed = validate.isDisposed(asset);
													Boolean isTransferred = validate.isTransferred(asset);
//													if (bulkRecord.getDocType().equals("WO")) {
//														errorMessage.append(validate.isGSTAsset(asset)
//																? value + ","
//																		+ "Cannot WriteOff Assets Purchased after GST"
//																: "");
//													} // Write Off of GST Assets implemented
													errorMessage.append(prInv.isEmpty() ? ""
															: value + "," + "Pending Purchase Invoice," + LS(prInv));
													errorMessage.append(dsInv.isEmpty() ? ""
															: value + "," + "Pending Sales Invoice," + LS(dsInv));
													errorMessage.append(dispDoc.isEmpty() ? ""
															: value + "," + "Pending Diposal," + LS(dispDoc));
													errorMessage.append(tranDoc.isEmpty() ? ""
															: value + "," + "Pending Transfer," + LS(tranDoc));
													errorMessage.append(dsOrd.isEmpty() ? ""
															: value + "," + "Pending Sales Order," + LS(dsOrd));
													errorMessage.append(
															(isDisposed) ? value + "," + "Already Disposed,\n" : "");
													errorMessage.append(
															(isTransferred) ? value + "," + "Already Transferred,\n"
																	: "");
													boolean wrongPass = dsInv.isEmpty() && dispDoc.isEmpty()
															&& dsOrd.isEmpty() && tranDoc.isEmpty()
															&& asset.isAmIsdisprocessing();
													errorMessage.append(
															wrongPass ? value + "," + "Pending Transfer/Disposal" : "");
												} else {
													errorMessage.append(value + "," + "Not belongs to "
															+ bulkRecord.getSrcOrg().getName() + "\n");
												}

											} else {
												errorMessage.append(value + "," + "Asset not found" + "\n");
											}
										} else {
											log.info("No Asset");
											errorMessage.append(value + "," + "AssetValue is Empty." + "\n");
										}
									}
								}
								size++;
							}
							count.incrementAndGet();
						} else {
							errorMessage.append(",EMPTY LINE\n");
						}
					});
					if (mixedMinions.contains("PRM") && bulkRecord.getDocType().equals("WO")) {
						message.setStatus(false);
						message.setMessage("Validations failed.");
						message.setDescription("Cannot WriteOff Premises.");
					} else if (mixedMinions.size() > 1) {
						if ((bulkRecord.getDocType().equals("WO"))) {
							message.setStatus(false);
							message.setMessage("Validations failed.");
							message.setDescription("IT & NonIT assets not allowed in the Same file.");
						} else {
							message.setStatus(false);
							message.setMessage("Validations failed. ");
							message.setDescription("Please Upload Asset typewise file.");
						}
					} else if (errorMessage.length() > 0) {
						message.setStatus(false);
						message.setMessage("Validations failed.");
						message.setDescription(
								"Please find the FailedValidations_" + bulkRecord.getDocNumber() + " in Attachments");
						generateErrorFile(FILENAME, "AssetValue,Error Message,DocNo." + "\n" + errorMessage.toString(),
								bulkRecord);
					} else {
						markAssets(FILENAME, bulkRecord, true);
						log.info("Passed all the validations.");
						message.setStatus(true);
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
				log.info("Time taken: " + timer.stop());
			}
			return message;
		}
	}

	/***
	 * @author satyamera108
	 * @param list
	 *            of document numbers
	 * @return Comma Separated Values
	 */
	public String LS(List<String> list) {
		return (list.toString().replace("[", "").replace("]", "") + "\n");
	}

	public void generateErrorFile(Path path, String message, BulkProcess req) {
		log.info("Generating error file...");
		final File csvSaveDir = new File(path.getParent().toString());
		if (!csvSaveDir.exists()) {
			csvSaveDir.mkdirs();
		}
		String csvFileName = "FailedValidations_" + req.getDocNumber() + ".csv";
		String csvFilePath = csvSaveDir + "/" + csvFileName;
		log.info("csvFilePath: " + csvFilePath);
		Attachment newattach = new Attachment();
		Table table = OBDal.getInstance().get(Table.class, "2B6510373EDC4A919DF949C07FB8AF3B");
		newattach.setOrganization(req.getOrganization());
		newattach.setClient(req.getClient());
		newattach.setSequenceNumber(10L);
		newattach.setName(csvFileName);
		newattach.setPath(path.subpath(3, 15).toString());
		newattach.setRecord(req.getId());
		newattach.setTable(table);
		newattach.setDataType("text/csv");
		try {
			try {
				File file = new File(csvFilePath);
				FileWriter fileWriter = new FileWriter(file);
				fileWriter.write(message);
				fileWriter.flush();
				OBDal.getInstance().save(newattach);
				fileWriter.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		} catch (Exception ex) {
			log.error("Error while creating Error file" + ex);
		}
	}

	public OBError markAssets(Path FILENAME, BulkProcess bulkRecord, Boolean vl) {
		OBError error = new OBError();
		log.info("Marking Assets . . .");
		String line = null;
		HashSet<String> lines = new HashSet<>();
		List<Asset> assetList = new ArrayList<>();
		log.info("FILE: " + FILENAME);
		try (BufferedReader br = new BufferedReader(new FileReader(FILENAME.toString()))) {
			br.readLine();
			while ((line = br.readLine()) != null) {
				if (lines.add(line)) {
					assetList.add(validate.getAsset(line));
				}
			}
			br.close();
		} catch (IOException e) {
			error.setType("error");
			error.setTitle("Error while Approving the request.");
			error.setMessage("Please contact CAMPS admin.");
		}
		for (Asset asset : assetList) {
			if (vl) {
				asset.setAmIsdisprocessing(true);
			} else {
				if (bulkRecord.getDocType().equals("WO")) {
					asset.setStatic(true);
					error.setType("Success");
				} else {
					asset.setAMISIDLE(true);
					error.setType("Success");
				}
			}
			asset.setHelpComment(bulkRecord.getDocNumber());
			int assetCount = 0;
			double purchaseCost = 0, bookValue = 0;
			for (Asset ast : assetList) {
				ast.setHelpComment(bulkRecord.getDocNumber());
				ast.setAmIsdisprocessing(true);
				assetCount++;
				purchaseCost = purchaseCost + ast.getAssetValue().doubleValue();
				bookValue = bookValue + ast.getAmBookvalue().doubleValue();
			}
			log.info("assetCount>" + assetCount + " purchaseCost>" + purchaseCost + " bookValue>" + bookValue);
			bulkRecord.setAssetCount(BigDecimal.valueOf(assetCount));
			bulkRecord.setPurchaseCost(BigDecimal.valueOf(purchaseCost));
			bulkRecord.setBookValue(BigDecimal.valueOf(bookValue));
			OBDal.getInstance().save(bulkRecord);
		}
		OBDal.getInstance().flush();

		log.info("Marked " + assetList.size() + " Assets.");
		return error;
	}
}
