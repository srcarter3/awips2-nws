/**
 * This software was developed and / or modified by NOAA/NWS/OCP/ASDT
 **/
package gov.noaa.nws.ocp.edex.climate.f6builder;

import java.io.IOException;
import java.io.StringWriter;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.exception.MethodInvocationException;
import org.apache.velocity.exception.ParseErrorException;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;

import com.raytheon.uf.common.message.StatusMessage;
import com.raytheon.uf.common.status.IUFStatusHandler;
import com.raytheon.uf.common.status.UFStatus;
import com.raytheon.uf.common.status.UFStatus.Priority;
import com.raytheon.uf.edex.core.EDEXUtil;
import com.raytheon.uf.edex.plugin.text.db.TextDB;

import gov.noaa.nws.ocp.common.dataplugin.climate.ClimateDate;
import gov.noaa.nws.ocp.common.dataplugin.climate.ClimateGlobal;
import gov.noaa.nws.ocp.common.dataplugin.climate.ClimateProdSendRecord;
import gov.noaa.nws.ocp.common.dataplugin.climate.ClimateRecordDay;
import gov.noaa.nws.ocp.common.dataplugin.climate.ClimateWind;
import gov.noaa.nws.ocp.common.dataplugin.climate.DailyClimateData;
import gov.noaa.nws.ocp.common.dataplugin.climate.PeriodClimo;
import gov.noaa.nws.ocp.common.dataplugin.climate.PeriodData;
import gov.noaa.nws.ocp.common.dataplugin.climate.PeriodType;
import gov.noaa.nws.ocp.common.dataplugin.climate.QueryData;
import gov.noaa.nws.ocp.common.dataplugin.climate.SLP;
import gov.noaa.nws.ocp.common.dataplugin.climate.Station;
import gov.noaa.nws.ocp.common.dataplugin.climate.exception.ClimateQueryException;
import gov.noaa.nws.ocp.common.dataplugin.climate.parameter.ParameterFormatClimate;
import gov.noaa.nws.ocp.common.dataplugin.climate.response.F6ServiceResponse;
import gov.noaa.nws.ocp.common.dataplugin.climate.util.ClimateUtilities;
import gov.noaa.nws.ocp.edex.common.climate.dao.ClimateDailyNormDAO;
import gov.noaa.nws.ocp.edex.common.climate.dao.ClimatePeriodNormDAO;
import gov.noaa.nws.ocp.edex.common.climate.dao.ClimateProdSendRecordDAO;
import gov.noaa.nws.ocp.edex.common.climate.dao.ClimateStationsSetupDAO;
import gov.noaa.nws.ocp.edex.common.climate.dao.DailyClimateDAO;
import gov.noaa.nws.ocp.edex.common.climate.dataaccess.ClimateGlobalConfiguration;
import gov.noaa.nws.ocp.edex.common.climate.util.ClimateDAOUtils;
import gov.noaa.nws.ocp.edex.common.climate.util.ClimateFileUtils;

/**
 * Build F6 Report(s) for given station(s) and month. Based on Legacy
 * adapt/climate/src/build_f6 modules.
 * 
 * <pre>
 * 
 * SOFTWARE HISTORY
 * 
 * Date         Ticket#    Engineer    Description
 * ------------ ---------- ----------- --------------------------
 * Nov 12, 2015            xzhang      Initial creation
 * 13 JUL 2016  20414      amoore      Cleaning up DAO's C-structure implementation.
 * 08 SEP 2016  20591      amoore      Add checks against out-of-bounds data in Period Data lists.
 * 28 SEP 2016  22166      jwu         Associate product files with PIL.
 * 29 SEP 2016  22139      jwu         Add mixed case velocity template.
 * 25 OCT 2016  22139      jwu         Use default pref.
 * 14 NOV 2016  22139      jwu         Return product content instead of files for VIZ use.
 * 22 NOV 2016  23222      amoore      Legacy DR 15685, verifying rounding. Identified potential
 *                                     future enhancement. Some clean up.
 * 28 NOV 2016  22930      astrakovsky Added comments explaining Julian day conversions.
 * 13 DEC 2016  27015      amoore      Query enhancements.
 * 22 FEB 2017  28609      amoore      Address TODOs. Fix comments.
 * 28 FEB 2017  27858      amoore      Fix up messages.
 * 10 MAR 2017  30130      amoore      F6 should not keep output in awips directory, and should
 *                                     delete after printing. Send to textDB on EDEX side, not VIZ.
 * 28 MAR 2017  30171      amoore      Modify cron job entry, clean up class.
 * 11 APR 2017  30171      amoore      Use proper rounding from Climate.
 * 12 APR 2017  30171      amoore      Clean up methods and messages.
 * 03 MAY 2017  33104      amoore      Use abstract map.
 * 03 MAY 2017  33533      amoore      F6 product notifications and tracking in DB.
 * 11 MAY 2017  33104      amoore      Update logging.
 * 11 MAY 2017  33104      amoore      Clean up variable logic.
 * 16 MAY 2017  33104      amoore      Floating point equality.
 * 14 JUN 2017  35175      amoore      Fix Precip calculations.
 * 14 JUN 2017  35177      amoore      Fix snow trace sum calculations. Fixed issue
 *                                     where not all branches of logic lead to each station
 *                                     having a message line in the F6 response.
 * 31 AUG 2017  37561      amoore      Final remarks processing should occur on backend.
 * </pre>
 * 
 * @author amoore
 * @version 1.0
 */

public class F6Builder {

    /**
     * Location of temporary F6 output, for printing.
     */
    private static final String F6_OUTPUT_LOCATION = "/tmp/climate/output/";

    /**
     * Velocity template file - all upper case.
     */
    private static final String VELOCITY_TEMP_ALL_UPPER_CASE = "/reportTemplates/f6_report.vm";

    /**
     * Velocity template file - mixed case.
     */
    private static final String VELOCITY_TEMP_MIXED_CASE = "/reportTemplates/f6_report_mixed_case.vm";

    // TODO put in common location
    public final static String EDEX = "EDEX";

    // TODO put in common location
    public final static String CATEGORY_INFO = "INFO";

    // TODO put in common location
    public static final String cpgEndpoint = "climateNotify";

    /**
     * Plugin ID for alerts.
     */
    public static final String PLUGIN_ID = "F6Builder";

    /**
     * Prefix for temporary F6 files.
     */
    private static final String OUTPUT_F6_PREFIX = "output_f6_";

    /** The logger */
    private static final IUFStatusHandler logger = UFStatus
            .getHandler(F6Builder.class);

    /**
     * Middle part of a PIL.
     * 
     * F6 product could be output into the text database with the PIL SSSLCDMMM,
     * where SSS represents the three-letter station id and MMM is the
     * three-letter month.
     */
    private final static String F6_PIL_MIDDLE = "LCD";

    private final DailyClimateDAO dailyClimateDao = new DailyClimateDAO();

    private final ClimateDailyNormDAO climateDailyNormDao = new ClimateDailyNormDAO();

    private final ClimatePeriodNormDAO climatePeriodNormDao = new ClimatePeriodNormDAO();

    private final ClimateStationsSetupDAO climateStationsSetupDao = new ClimateStationsSetupDAO();

    /**
     * Constructor.
     */
    public F6Builder() {
    }

    /**
     * routine call entry, for previous day's month
     * 
     * @return
     * @throws ClimateQueryException
     */
    public F6ServiceResponse buildF6() throws ClimateQueryException {
        List<Station> stations;
        try {
            stations = climateStationsSetupDao.getMasterStations();
        } catch (Exception e) {
            logger.error(
                    "Failed to get master stations! Check database connection.",
                    e);
            throw new ClimateQueryException("Failed to get statiosn for F6.",
                    e);
        }
        if (stations.size() == 0) {
            logger.error("No station data found in database.");
            throw new ClimateQueryException("No stations listed for F6.");
        }

        ClimateDate aDate = ClimateDate.getPreviousDay();
        return buildF6ForStations(stations, aDate, "", false, true);
    }

    /**
     * UI call entry
     * 
     * @param stations
     * @param aDate
     * @param remarks
     * @param print
     * @param operational
     * @return
     */
    public F6ServiceResponse buildF6ForStations(List<Station> stations,
            ClimateDate aDate, String remarks, boolean print,
            boolean operational) {

        boolean hasException = false;
        StringBuilder sb = new StringBuilder();
        Map<String, String> fileMap = new HashMap<>();
        Map<String, List<String>> pilMap = new HashMap<>();

        SimpleDateFormat fmt = new SimpleDateFormat("MMM");
        String mon = fmt.format(aDate.getCalendarFromClimateDate().getTime())
                .toUpperCase();

        ClimateProdSendRecordDAO sendRecordDAO = new ClimateProdSendRecordDAO();

        TextDB textdb = new TextDB();

        for (Station station : stations) {
            // File name to match legacy
            String fileName = OUTPUT_F6_PREFIX + station.getIcaoId();

            // PIL name to be used later for text DB.
            String pilName = station.getIcaoId().substring(1) + F6_PIL_MIDDLE
                    + mon;
            try {
                List<String> reportContent = buildF6ForStation(station, aDate,
                        remarks);

                if (print) {
                    fileMap.put(fileName, F6_OUTPUT_LOCATION + fileName);
                    ClimateFileUtils.writeStringsToFile(reportContent,
                            F6_OUTPUT_LOCATION, fileName);
                }

                StringBuilder totalContents = new StringBuilder();
                for (String line : reportContent) {
                    totalContents.append(line).append("\n");
                }

                long insertTime = textdb.writeProduct(pilName,
                        totalContents.toString(), operational, null);

                if (insertTime != Long.MIN_VALUE) {
                    logger.info(
                            "Successfully stored F6 report to the text database for station ["
                                    + station.getIcaoId() + "]");

                    // store record of product to DB
                    try {
                        ClimateProdSendRecord record = new ClimateProdSendRecord();
                        record.setProd_id(pilName);
                        record.setProd_type("F6");
                        record.setProd_text(totalContents.toString());
                        record.setFile_name(fileName);
                        record.setSend_time(new Timestamp(insertTime));
                        record.setUser_id("auto");

                        sendRecordDAO.insertSentClimateProdRecord(record);
                    } catch (ClimateQueryException e) {
                        logger.error("Failed to track F6 report with PIL ["
                                + pilName + "] and text body [" + totalContents
                                + "].", e);
                    }

                    // send product alarm alert
                    try {
                        StatusMessage sm = new StatusMessage();
                        sm.setPriority(Priority.EVENTA);
                        sm.setPlugin(PLUGIN_ID);
                        sm.setCategory(CATEGORY_INFO);
                        sm.setMessage(
                                "F6 product [" + pilName + "] generated.");
                        sm.setMachineToCurrent();
                        sm.setSourceKey(EDEX);
                        sm.setDetails("Stored F6 report with AFOS ID ["
                                + pilName + "] and text body [" + totalContents
                                + "]");
                        sm.setEventTime(new Date(insertTime));

                        EDEXUtil.getMessageProducer().sendAsync(cpgEndpoint,
                                sm);
                    } catch (Exception e) {
                        logger.error("Could not send message to ClimateView",
                                e);
                    }
                    pilMap.put(pilName, reportContent);

                    sb.append("Successfully created report for Station "
                            + station.getStationName() + ".\n");
                } else {
                    String message = "Something went wrong storing F6 report to the text database for station "
                            + station.getIcaoId();
                    logger.error(message);
                    sb.append(message);
                    hasException = true;
                }
            } catch (Exception e) {
                logger.error(
                        "Something went wrong during creation of F6 report for station "
                                + station.getIcaoId(),
                        e);
                hasException = true;
                sb.append(
                        "Something went wrong during creation of F6 report for station "
                                + station.getStationName()
                                + ". Check EDEX log for details.\n");
            }
        }

        if (print) {
            try {
                // print reports
                ClimateFileUtils
                        .printFiles(new ArrayList<String>(fileMap.values()));
            } catch (IOException e) {
                logger.error("Failed to print F6 reports.", e);
            }
            try {
                // delete reports
                ClimateFileUtils
                        .deleteFiles(new ArrayList<String>(fileMap.values()));
            } catch (IOException e) {
                logger.error("Failed to delete F6 reports after printing.", e);
            }
        }

        return new F6ServiceResponse(pilMap, !hasException, sb.toString());
    }

    /**
     * 
     * @param station
     * @param aDate
     * @param remarks
     * @return
     * @throws ClimateQueryException
     */
    private List<String> buildF6ForStation(Station station, ClimateDate aDate,
            String remarks) throws ClimateQueryException {
        List<String> lines = new ArrayList<>();

        // create latitude/longitude in degrees/minutes
        int latdeg = (int) station.getDlat();
        int latmin = (int) ((station.getDlat() - latdeg) * 60.);

        int longdeg = (int) station.getDlon();
        int longmin = ClimateUtilities
                .nint((station.getDlon() - longdeg) * 60.);

        String latDir = "N";
        String longDir = "W";

        if (longdeg < 0) {
            longdeg *= (-1);
            longDir = "E";
        }
        /*
         * Task 26505: should negative latitude also be handled as above?
         * American Samoa has negative latitude (about 14 degrees South).
         */

        // use template
        VelocityEngine ve = new VelocityEngine();
        ve.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath");
        ve.setProperty("classpath.resource.loader.class",
                ClasspathResourceLoader.class.getName());

        ve.init();

        VelocityContext context = new VelocityContext();

        /*
         * Get the Template - use either all upper case or mixed case. Default
         * is to use all upper case.
         */
        ClimateGlobal prefs = ClimateGlobalConfiguration.getGlobal();
        if (prefs == null) {
            prefs = ClimateGlobal.getMissingClimateGlobal();
        }
        boolean allUpperCase = prefs.isNoSmallLetters();

        String vmTempFile;
        if (allUpperCase) {
            vmTempFile = VELOCITY_TEMP_ALL_UPPER_CASE;
        } else {
            vmTempFile = VELOCITY_TEMP_MIXED_CASE;
        }

        context.put("stationName", station.getStationName());
        String month = ClimateDate.getMonthStringFromClimateDate(aDate);
        if (allUpperCase) {
            month = month.toUpperCase();
        }
        context.put("month", month);
        context.put("year", ClimateDate.getYearStringFromClimateDate(aDate));
        context.put("latdeg", String.format("%3s", latdeg));
        context.put("latmin", String.format("%3s", latmin));
        context.put("north", latDir);
        context.put("longdeg", String.format("%3s", longdeg));
        context.put("longmin", String.format("%3s", longmin));
        context.put("west", longDir);

        // Build the climatology line for the day
        ClimateDate f6Date = new ClimateDate(aDate);

        int sumMax = 0;
        int sumMin = 0;
        int sumHdd = 0;
        int sumCdd = 0;
        int sumSS = 0;
        int sumPsbl = 0;
        float sumWtr = 0;
        float sumSnw = 0;
        float sumAvgspd = 0;
        int sumMinSun = 0;

        int numMax = 0;
        int numMin = 0;
        int numHdd = 0;
        int numCdd = 0;
        int numSS = 0;
        int numPsbl = 0;
        int numWtr = 0;
        int numNonTraceSnow = 0;
        int numPositiveSnow = 0;
        int numTraceSnow = 0;
        int numAvgspd = 0;
        int numMinSun = 0;
        int numWtrT = 0;

        List<Map<String, String>> dailyList = new ArrayList<>();

        DailyClimateData dailyData = DailyClimateData
                .getMissingDailyClimateData();

        for (int i = 0; i < aDate.getDay(); i++) {

            Map<String, String> dailyValueMap = new HashMap<>();

            int avgTemp = 0;
            int deptTemp;
            int normTemp; /* dept_temp is departure from normal */

            dailyValueMap.put("dy", String.format("%2s", i + 1));

            f6Date.setDay(i + 1);

            QueryData queryData = dailyClimateDao.getLastYear(f6Date,
                    station.getInformId());
            if (queryData.getExists()) {
                dailyData = (DailyClimateData) queryData.getData();
            } else {
                logger.warn("No data for day: [" + f6Date.toFullDateString()
                        + "] for station ID: [" + station.getInformId() + "]");
                dailyData = DailyClimateData.getMissingDailyClimateData();
            }

            ClimateRecordDay historyData;
            try {
                historyData = climateDailyNormDao.getHistoricalNorms(f6Date,
                        station.getInformId());
            } catch (ClimateQueryException e) {
                logger.error("Error getting historical data.", e);
                historyData = ClimateRecordDay.getMissingClimateRecordDay();
            }

            ClimateDAOUtils.buildDerivedData(f6Date, station.getInformId(),
                    dailyData);

            // begin to handle values:
            if (dailyData.getMaxTemp() != ParameterFormatClimate.MISSING) {
                dailyValueMap.put("max",
                        String.format("%4s", dailyData.getMaxTemp()));
                sumMax += dailyData.getMaxTemp();
                numMax++;
            } else {
                dailyValueMap.put("max", String.format("%4s", "M"));
            }

            if (dailyData.getMinTemp() != ParameterFormatClimate.MISSING) {
                dailyValueMap.put("min",
                        String.format("%4s", dailyData.getMinTemp()));
                sumMin += dailyData.getMinTemp();
                numMin++;
            } else {
                dailyValueMap.put("min", String.format("%4s", "M"));
            }

            if (dailyData.getMaxTemp() != ParameterFormatClimate.MISSING
                    && dailyData
                            .getMinTemp() != ParameterFormatClimate.MISSING) {
                // no need ninttemp
                avgTemp = ClimateUtilities
                        .nint((dailyData.getMaxTemp() + dailyData.getMinTemp())
                                / 2.0f);

                dailyValueMap.put("avg", String.format("%4s", avgTemp));
            } else {
                dailyValueMap.put("avg", String.format("%4s", "M"));
            }

            /* temperature departure from normal */
            if (historyData.getMeanTemp() != ParameterFormatClimate.MISSING
                    && dailyData.getMaxTemp() != ParameterFormatClimate.MISSING
                    && dailyData
                            .getMinTemp() != ParameterFormatClimate.MISSING) {
                normTemp = ClimateUtilities.nint(historyData.getMeanTemp());
                deptTemp = avgTemp - normTemp;
                dailyValueMap.put("dep", String.format("%4s", deptTemp));
            } else if (historyData
                    .getMinTempMean() != ParameterFormatClimate.MISSING
                    && historyData
                            .getMaxTempMean() != ParameterFormatClimate.MISSING
                    && dailyData.getMaxTemp() != ParameterFormatClimate.MISSING
                    && dailyData
                            .getMinTemp() != ParameterFormatClimate.MISSING) {

                normTemp = ClimateUtilities.nint((historyData.getMinTempMean()
                        + historyData.getMaxTempMean()) / 2.0f);

                deptTemp = avgTemp - normTemp;
                dailyValueMap.put("dep", String.format("%4s", deptTemp));
            } else {
                dailyValueMap.put("dep", String.format("%4s", "M"));
            }

            /* heating degree days */
            if (dailyData
                    .getNumHeat() != ParameterFormatClimate.MISSING_DEGREE_DAY) {
                dailyValueMap.put("hdd",
                        String.format("%4s", dailyData.getNumHeat()));
                sumHdd += dailyData.getNumHeat();
                numHdd++;
            } else {
                dailyValueMap.put("hdd", String.format("%4s", "M"));
            }

            /* cooling degree days */
            if (dailyData
                    .getNumCool() != ParameterFormatClimate.MISSING_DEGREE_DAY) {
                dailyValueMap.put("cdd",
                        String.format("%4s", dailyData.getNumCool()));
                sumCdd += dailyData.getNumCool();
                numCdd++;
            } else {
                dailyValueMap.put("cdd", String.format("%4s", "M"));
            }

            /* precipitation */
            if (dailyData.getPrecip() != ParameterFormatClimate.MISSING) {
                if (dailyData.getPrecip() == ParameterFormatClimate.TRACE) {
                    dailyValueMap.put("wtr", String.format("%5s", "T"));
                    numWtrT++;
                } else {
                    dailyValueMap.put("wtr",
                            String.format("%5.2f", dailyData.getPrecip()));
                    sumWtr += dailyData.getPrecip();
                    numWtr++;
                }
            } else {
                dailyValueMap.put("wtr", String.format("%5s", "M"));
            }

            /* snow amount */
            if (dailyData.getSnowDay() != ParameterFormatClimate.MISSING) {
                if (dailyData.getSnowDay() == ParameterFormatClimate.TRACE) {
                    dailyValueMap.put("snw", String.format("%5s", "T"));
                    numTraceSnow++;
                    // if the snow sum is 0, at least make it trace now
                    if (sumSnw == 0) {
                        sumSnw = ParameterFormatClimate.TRACE;
                    }
                } else {
                    dailyValueMap.put("snw",
                            String.format("%5.1f", dailyData.getSnowDay()));
                    numNonTraceSnow++;
                    if (dailyData.getSnowDay() != 0) {
                        numPositiveSnow++;
                        // no point in summing 0
                        if (sumSnw == ParameterFormatClimate.TRACE) {
                            /*
                             * current sum is trace; set to this non-zero amount
                             * instead
                             */
                            sumSnw = dailyData.getSnowDay();
                        } else {
                            /*
                             * current sum is not trace; add
                             */
                            sumSnw += dailyData.getSnowDay();
                        }
                    }
                }
            } else {
                dailyValueMap.put("snw", String.format("%5s", "M"));
            }

            /* snow on the ground */
            if (dailyData.getSnowGround() != ParameterFormatClimate.MISSING) {
                if (dailyData.getSnowGround() == ParameterFormatClimate.TRACE) {
                    dailyValueMap.put("dpth", String.format("%5s", "T"));
                } else {
                    dailyValueMap.put("dpth",
                            String.format("%5.0f", dailyData.getSnowGround()));
                }
            } else {
                dailyValueMap.put("dpth", String.format("%5s", "M"));
            }

            /* average wind speed */
            if (dailyData.getAvgWindSpeed() < ParameterFormatClimate.MISSING) {
                dailyValueMap.put("spd",
                        String.format("%5.1f", dailyData.getAvgWindSpeed()));
                sumAvgspd += dailyData.getAvgWindSpeed();
                numAvgspd++;
            } else {
                dailyValueMap.put("spd", String.format("%5s", "M"));
            }

            /* maximum 2-min wind speed */
            if (dailyData.getMaxWind()
                    .getSpeed() < ParameterFormatClimate.MISSING) {
                int ispeed = ClimateUtilities
                        .nint(dailyData.getMaxWind().getSpeed());
                dailyData.getMaxWind().setSpeed(ispeed);
                dailyValueMap.put("mxspd", String.format("%3s", ispeed));
            } else {
                dailyValueMap.put("mxspd", String.format("%3s", "M"));
            }

            /* maximum 2-min wind direction */
            if (dailyData.getMaxWind()
                    .getDir() < ParameterFormatClimate.MISSING) {
                dailyValueMap.put("dir",
                        String.format("%4s", dailyData.getMaxWind().getDir()));
            } else {
                dailyValueMap.put("dir", String.format("%4s", "M"));
            }

            if (dailyData.getMinutesSun() != ParameterFormatClimate.MISSING) {
                dailyValueMap.put("minsun",
                        String.format("%4s", dailyData.getMinutesSun()));

                sumMinSun += dailyData.getMinutesSun();
                numMinSun++;
            } else {
                dailyValueMap.put("minsun", String.format("%4s", "M"));
            }

            /* percent of possible sunshine */
            if (dailyData
                    .getPercentPossSun() != ParameterFormatClimate.MISSING) {
                dailyValueMap.put("psbl",
                        String.format("%5s", dailyData.getPercentPossSun()));

                sumPsbl += dailyData.getPercentPossSun();
                numPsbl++;
            } else {
                dailyValueMap.put("psbl", String.format("%5s", "M"));
            }

            /*
             * Legacy documentation:
             * 
             * S-S, think this is average sky cover but will try to switch to
             * octas well, maybe not based on part 2 - will have to check
             * 
             * Correction: "okta"
             */

            if (dailyData.getSkyCover() < ParameterFormatClimate.MISSING) {

                /*
                 * Legacy documentation:
                 * 
                 * convert to whole octas?
                 * 
                 * Correction: "okta"
                 */
                int avgSky = ClimateUtilities
                        .nint(dailyData.getSkyCover() * 10);
                sumSS += avgSky;
                numSS++;
                dailyValueMap.put("ss", String.format("%4s", avgSky));
            } else {
                dailyValueMap.put("ss", String.format("%4s", "M"));
            }

            /* weather */
            StringBuilder wxsb = new StringBuilder();
            if (dailyData.getWxType()[12] == 1) {
                wxsb.append("1");
            }
            if (dailyData.getWxType()[13] == 1) {
                wxsb.append("2");
            }
            if (dailyData.getWxType()[0] == 1) {
                wxsb.append("3");
            }
            if (dailyData.getWxType()[11] == 1) {
                wxsb.append("4");
            }
            if (dailyData.getWxType()[7] == 1) {
                wxsb.append("5");
            }
            if (dailyData.getWxType()[5] == 1
                    || dailyData.getWxType()[6] == 1) {
                wxsb.append("6");
            }
            if (dailyData.getWxType()[16] == 1) {
                wxsb.append("7");
            }
            if (dailyData.getWxType()[14] == 1) {
                wxsb.append("8");
            }
            if (dailyData.getWxType()[15] == 1) {
                wxsb.append("9");
            }
            if (dailyData.getWxType()[17] == 1) {
                wxsb.append("X");
            }

            if (dailyData.getNumWx() != ParameterFormatClimate.MISSING) {
                dailyValueMap.put("ws",
                        String.format(" %-4s", wxsb.toString()));
            } else {
                dailyValueMap.put("ws", String.format(" %-4s", "M"));
            }

            /* maximum gust or peak wind speed */
            if (dailyData.getMaxGust()
                    .getSpeed() < ParameterFormatClimate.MISSING) {
                int ispeed = ClimateUtilities
                        .nint(dailyData.getMaxGust().getSpeed());
                dailyData.getMaxGust().setSpeed(ispeed);
                dailyValueMap.put("isp", String.format("%3s", ispeed));
            } else {
                dailyValueMap.put("isp", String.format("%3s", "M"));
            }

            /* direction of maximum gust or peak */
            if (dailyData.getMaxGust()
                    .getDir() < ParameterFormatClimate.MISSING) {

                dailyValueMap.put("dr", String.format("%4s",
                        (dailyData.getMaxGust().getDir())));
            } else {
                dailyValueMap.put("dr", String.format("%4s", "M"));
            }

            dailyList.add(dailyValueMap);
        }

        context.put("dailyList", dailyList);
        /* end daily data */

        /*-------------------Start summation data------------------------*/
        // calculate sum from above
        // sum_maxtemp
        if (numMax > 0) {
            context.put("sumMax", String.format("%5s", sumMax));
        } else {
            context.put("sumMax", String.format("%5s", "M"));
        }

        // sum_minTemp
        if (numMin > 0) {
            context.put("sumMin", String.format("%5s", sumMin));
        } else {
            context.put("sumMin", String.format("%5s", "M"));
        }

        /* sum of heating degree days */
        if (numHdd > 0) {
            context.put("sumHdd", String.format("%4s", sumHdd));
        } else {
            context.put("sumHdd", String.format("%4s", "M"));
        }

        /* sum of cooling degree days */
        if (numCdd > 0) {
            context.put("sumCdd", String.format("%4s", sumCdd));
        } else {
            context.put("sumCdd", String.format("%4s", "M"));
        }

        /* sum of precipitation totals */
        if (numWtr > 0) {
            context.put("sumWtr", String.format("%6.2f", sumWtr));
        } else if (numWtrT > 0) {
            context.put("sumWtr", String.format("%6s", "T"));
        } else {
            context.put("sumWtr", String.format("%6s", "M"));
        }

        /* sum of snowfall total */
        if (numNonTraceSnow > 0 && numPositiveSnow > 0) {
            // at least one positive snow event
            context.put("sumSnw", String.format("%5.1f", sumSnw));
        } else if (numTraceSnow > 0) {
            // only trace snow and 0 snow
            context.put("sumSnw", String.format("%5s", "T"));
        } else if (numNonTraceSnow > 0) {
            // only 0-precip snow events
            context.put("sumSnw", String.format("%5s", "0.0"));
        } else {
            // no snow events
            context.put("sumSnw", String.format("%5s", "M"));
        }

        /* sum of average wind speed for month */
        if (numAvgspd > 0) {
            context.put("sumAvgspd", String.format("%6.1f", sumAvgspd));
        } else {
            context.put("sumAvgspd", String.format("%6s", "M"));
        }

        if (numMinSun > 0) {
            context.put("sumMinSun", String.format("%5s", sumMinSun));
        } else {
            context.put("sumMinSun", String.format("%5s", "M"));
        }

        if (numSS > 0) {
            context.put("sumSS", String.format("%4s", sumSS));
        } else {
            context.put("sumSS", String.format("%4s", "M"));
        }

        /*-------------------- start Average information placement section--------------------*/
        // avg_maxtemp
        float avgMaxTemp = ParameterFormatClimate.MISSING;
        float avgMinTemp = ParameterFormatClimate.MISSING;
        float avgTempf = ParameterFormatClimate.MISSING;
        ;

        if (numMax > 0) {
            avgMaxTemp = (sumMax + 0.0f) / numMax;
            context.put("avgmax", String.format("%5.1f",
                    ClimateUtilities.nint(avgMaxTemp, 1)));
        } else {
            context.put("avgmax", String.format("%5s", "M"));
        }

        // avg_minTemp
        if (numMin > 0) {
            avgMinTemp = (sumMin + 0.0f) / numMin;
            context.put("avgmin", String.format("%5.1f",
                    ClimateUtilities.nint(avgMinTemp, 1)));
        } else {
            context.put("avgmin", String.format("%5s", "M"));
        }

        if (numMax > 0 && numMin > 0) {
            avgTempf = ((avgMaxTemp + avgMinTemp) / 2.0f); /* for page 2 */
            context.put("avgTempf",
                    String.format("%5.1f", ClimateUtilities.nint(avgTempf, 1)));
        } else {
            context.put("avgTempf", String.format("%5s", "M"));
        }

        if (numAvgspd > 0) {
            context.put("avgspd",
                    String.format("%5.1f", sumAvgspd / numAvgspd));
        } else {
            context.put("avgspd", String.format("%5s", "M"));
        }

        if (numMinSun > 0) {
            context.put("avgms", String.format("%4s",
                    (int) ((float) sumMinSun / numMinSun + 0.5)));
        } else {
            context.put("avgms", String.format("%4s", "M"));
        }

        if (numPsbl > 0) {
            context.put("avgpsbl", String.format("%5s",
                    (int) ((((double) sumPsbl) / numPsbl) + 0.5)));
        } else {
            context.put("avgpsbl", String.format("%5s", "M"));
        }

        if (numSS > 0) {
            context.put("avgss", String.format("%4s", sumSS / numSS));
        } else {
            context.put("avgss", String.format("%4s", "M"));
        }

        /* maximum 2-minute wind speed for month */
        List<ClimateWind> maxMaxWind = dailyClimateDao.getMaxMaxWind(aDate,
                station.getInformId());

        /* maximum wind gust or peak wind */
        List<ClimateWind> maxGustWind = dailyClimateDao.getMaxGustWind(aDate,
                station.getInformId());

        context.put("maxflag", " ");
        if (maxMaxWind.size() == 0) {
            context.put("maxspd", String.format("%3s", "M"));
            context.put("maxdir", String.format("%3s", "M"));
        } else {
            context.put("maxspd", String.format("%3s",
                    ClimateUtilities.nint(maxMaxWind.get(0).getSpeed())));
            context.put("maxdir",
                    String.format("%3s", maxMaxWind.get(0).getDir()));
            if (maxMaxWind.size() > 1) {
                context.put("maxflag", "#");
            }
        }

        context.put("maxgflag", " ");
        if (maxGustWind.size() == 0) {
            context.put("maxgspd", String.format("%3s", "M"));
            context.put("maxgdir", String.format("%3s", "M"));
        } else {
            context.put("maxgspd", String.format("%3s",
                    ClimateUtilities.nint(maxGustWind.get(0).getSpeed())));
            context.put("maxgdir",
                    String.format("%3s", maxGustWind.get(0).getDir()));
            if (maxGustWind.size() > 1) {
                context.put("maxgflag", "#");
            }
        }

        /*******************************
         * START PAGE 2 OF F-6 HERE
         **********************************/

        /*******************************************************
         * Let's get the monthly obs data and normals
         *******************************************************/
        PeriodClimo pClimo = PeriodClimo.getMissingPeriodClimo();

        pClimo.setInformId(station.getInformId());

        ClimateDate beginDate = new ClimateDate(1, aDate.getMon(),
                aDate.getYear());
        climatePeriodNormDao.getPeriodHistClimo(beginDate, aDate, pClimo,
                PeriodType.MONTHLY_RAD);
        PeriodData periodData = dailyClimateDao.buildMonthObsClimo(beginDate,
                aDate, station.getInformId());

        /* place precipitation total for the month */
        if (periodData.getPrecipTotal() != ParameterFormatClimate.MISSING) {
            if (periodData.getPrecipTotal() > ParameterFormatClimate.TRACE) {
                context.put("tfm",
                        String.format("%7.2f", periodData.getPrecipTotal()));
            } else {
                context.put("tfm", String.format("%7s", "T"));
            }
        } else {
            context.put("tfm", String.format("%7s", "M"));
        }

        /* place temperature departure from normal */
        if (pClimo.getNormMeanTemp() != ParameterFormatClimate.MISSING
                && periodData.getMeanTemp() != ParameterFormatClimate.MISSING) {
            context.put("dptrtemp", String.format("%5.1f",
                    (avgTempf - pClimo.getNormMeanTemp())));
        } else {
            context.put("dptrtemp", String.format("%5s", "M"));
        }

        if (pClimo.getPrecipPeriodNorm() != ParameterFormatClimate.MISSING
                && periodData
                        .getPrecipTotal() != ParameterFormatClimate.MISSING) {
            float dptrprcp;
            if (periodData.getPrecipTotal() == -1.
                    && pClimo.getPrecipPeriodNorm() == -1.) {
                dptrprcp = 0.0f;
            } else if (periodData.getPrecipTotal() == -1.
                    && pClimo.getPrecipPeriodNorm() > -1.) {
                dptrprcp = (-1.0f) * pClimo.getPrecipPeriodNorm();
            } else if (periodData.getPrecipTotal() > -1.
                    && pClimo.getPrecipPeriodNorm() == -1.) {
                dptrprcp = periodData.getPrecipTotal();
            } else {
                dptrprcp = periodData.getPrecipTotal()
                        - pClimo.getPrecipPeriodNorm();
            }

            context.put("dptrprcp", String.format("%8.2f", dptrprcp));
        } else {
            context.put("dptrprcp", String.format("%8s", "M"));
        }

        /* place maximum temperature of month */
        if (periodData.getMaxTemp() != ParameterFormatClimate.MISSING) {

            int maxTempDayListSize = periodData.getDayMaxTempList().size();

            context.put("maxtemp",
                    String.format("%4d", periodData.getMaxTemp()));
            if (maxTempDayListSize >= 1 && periodData.getDayMaxTempList().get(0)
                    .getDay() != ParameterFormatClimate.MISSING_DATE) {
                context.put("mtday", String.format("%2d",
                        periodData.getDayMaxTempList().get(0).getDay()));

            } else {
                context.put("mtday", " M");
            }

            if (maxTempDayListSize >= 2 && periodData.getDayMaxTempList().get(1)
                    .getDay() != ParameterFormatClimate.MISSING_DATE) {
                context.put("mtday2", String.format(",%2d",
                        periodData.getDayMaxTempList().get(1).getDay()));

            } else {
                context.put("mtday2", "  ");
            }
        } else {
            context.put("maxtemp", String.format("%4s", "M"));
            context.put("mtday", " M");
            context.put("mtday2", "  ");
        }
        /* place 24-hour maximum precipitation and dates */
        if (periodData.getPrecipMax24H() != ParameterFormatClimate.MISSING) {
            if (periodData.getPrecipMax24H() > ParameterFormatClimate.TRACE) {
                context.put("precipMax24H",
                        String.format("%5.2f", periodData.getPrecipMax24H()));
            } else {
                context.put("precipMax24H", String.format("%5s", "T"));
            }
            if (periodData.getPrecip24HDates().size() >= 1
                    && periodData.getPrecip24HDates().get(0).getStart()
                            .getDay() != ParameterFormatClimate.MISSING_DATE) {
                context.put("precip24H1",
                        String.format("%2d-%2d",
                                periodData.getPrecip24HDates().get(0).getStart()
                                        .getDay(),
                                periodData.getPrecip24HDates().get(0).getEnd()
                                        .getDay()));
            } else {
                context.put("precip24H1", " M");
            }
        } else {
            context.put("precipMax24H", String.format("%6s", "M"));
            context.put("precip24H1", " M ");
        }

        /* place minimum temperature */
        context.put("mintday2", "  ");
        if (periodData.getMinTemp() != ParameterFormatClimate.MISSING) {

            int minTempDayListSize = periodData.getDayMinTempList().size();

            context.put("mintemp",
                    String.format("%4d", periodData.getMinTemp()));
            if (minTempDayListSize >= 1 && periodData.getDayMinTempList().get(0)
                    .getDay() != ParameterFormatClimate.MISSING_DATE) {
                context.put("mintday", String.format("%2d",
                        periodData.getDayMinTempList().get(0).getDay()));

            } else {
                context.put("mintday", " M");
            }

            if (minTempDayListSize >= 2 && periodData.getDayMinTempList().get(1)
                    .getDay() != ParameterFormatClimate.MISSING_DATE) {
                context.put("mintday2", String.format(",%2d",
                        periodData.getDayMinTempList().get(1).getDay()));

            }
        } else {
            context.put("mintemp", String.format("%4s", "M"));
            context.put("mintday", " M");
        }

        /* place snow total for the month */
        if (periodData.getSnowTotal() != ParameterFormatClimate.MISSING) {
            if (periodData.getSnowTotal() > ParameterFormatClimate.TRACE) {
                if (periodData.getSnowTotal() >= 0
                        && periodData.getSnowTotal() <= 1) {
                    context.put("snowTotal", String.format("%6.1f INCH  ",
                            periodData.getSnowTotal()));
                } else {
                    context.put("snowTotal", String.format("%6.1f INCHES",
                            periodData.getSnowTotal()));
                }

            } else {
                context.put("snowTotal", String.format("%-13s", "  T"));
            }
        } else {
            context.put("snowTotal", String.format("%-13s", "  M"));
        }

        /* place 24-hour maximum snow and dates */
        if (periodData.getSnowMax24H() != ParameterFormatClimate.MISSING) {
            if (periodData.getSnowMax24H() != 0.0) {
                if (periodData
                        .getSnowMax24H() != ParameterFormatClimate.TRACE) {
                    context.put("snowMax24H",
                            String.format("%5.1f", periodData.getSnowMax24H()));
                } else {
                    context.put("snowMax24H", "  T  ");
                }

                if (periodData.getSnow24HDates().size() >= 1 && periodData
                        .getSnow24HDates().get(0).getStart()
                        .getDay() != ParameterFormatClimate.MISSING_DATE) {
                    context.put("snowMax24Hday",
                            String.format("%2d-%2d",
                                    periodData.getSnow24HDates().get(0)
                                            .getStart().getDay(),
                                    periodData.getSnow24HDates().get(0).getEnd()
                                            .getDay()));
                } else {
                    context.put("snowMax24Hday", "  M  ");
                }
                context.put("flagON", "ON");
            } else {
                context.put("snowMax24H", " 0.0 ");
                context.put("snowMax24Hday", "     ");
                context.put("flagON", "  ");
            }
        } else {
            context.put("snowMax24H", "   M ");
            context.put("snowMax24Hday", "  M  ");
            context.put("flagON", "ON");
        }

        /* place maximum snow depth and dates for month */
        if (periodData.getSnowGroundMax() != ParameterFormatClimate.MISSING
                && periodData.getSnowGroundMax() != 0
                && periodData.getSnowGroundMax() != -1) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%3d", periodData.getSnowGroundMax()));

            int snowGroundMaxDateListSize = periodData
                    .getSnowGroundMaxDateList().size();

            if (snowGroundMaxDateListSize >= 1
                    && periodData.getSnowGroundMaxDateList().get(0)
                            .getDay() != ParameterFormatClimate.MISSING_DATE) {
                sb.append(" ON ");
                sb.append(String.format("%2d",
                        periodData.getSnowGroundMaxDateList().get(0).getDay()));
            } else {
                sb.append(" ON M");
            }
            if (snowGroundMaxDateListSize >= 2
                    && periodData.getSnowGroundMaxDateList().get(1)
                            .getDay() != ParameterFormatClimate.MISSING_DATE) {
                sb.append(String.format(",%2d",
                        periodData.getSnowGroundMaxDateList().get(1).getDay()));
            }
            context.put("gdepth", String.format("%-12s", sb.toString()));
        } else if (periodData.getSnowGroundMax() == -1) {
            context.put("gdepth", String.format("%-12s", " T"));
        } else if (periodData.getSnowGroundMax() == 0) {
            context.put("gdepth", String.format("%-12s", " 0"));
        } else {
            context.put("gdepth", String.format("%-12s", " M  ON   M"));
        }

        /*
         * place temperature and precipitation above and below threshold values
         */
        if (periodData
                .getNumMaxLessThan32F() != ParameterFormatClimate.MISSING) {
            context.put("max32",
                    String.format("%3s", periodData.getNumMaxLessThan32F()));
        } else {
            context.put("max32", "  M");
        }

        if (periodData
                .getNumPrcpGreaterThan01() != ParameterFormatClimate.MISSING) {
            context.put("max32i",
                    String.format("%3s", periodData.getNumPrcpGreaterThan01()));
        } else {
            context.put("max32i", "  M");
        }

        if (periodData
                .getNumMaxGreaterThan90F() != ParameterFormatClimate.MISSING) {
            context.put("max90",
                    String.format("%3s", periodData.getNumMaxGreaterThan90F()));
        } else {
            context.put("max90", "  M");
        }

        if (periodData
                .getNumPrcpGreaterThan10() != ParameterFormatClimate.MISSING) {
            context.put("max90i",
                    String.format("%3s", periodData.getNumPrcpGreaterThan10()));
        } else {
            context.put("max90i", "  M");
        }

        if (periodData
                .getNumMinLessThan32F() != ParameterFormatClimate.MISSING) {
            context.put("min32",
                    String.format("%3s", periodData.getNumMinLessThan32F()));
        } else {
            context.put("min32", "  M");
        }

        if (periodData
                .getNumPrcpGreaterThan50() != ParameterFormatClimate.MISSING) {
            context.put("min32i",
                    String.format("%3s", periodData.getNumPrcpGreaterThan50()));
        } else {
            context.put("min32i", "  M");
        }

        if (periodData
                .getNumMinLessThan0F() != ParameterFormatClimate.MISSING) {
            context.put("min0",
                    String.format("%3s", periodData.getNumMinLessThan0F()));
        } else {
            context.put("min0", "  M");
        }

        if (periodData
                .getNumPrcpGreaterThan100() != ParameterFormatClimate.MISSING) {
            context.put("min0i", String.format("%3s",
                    periodData.getNumPrcpGreaterThan100()));
        } else {
            context.put("min0i", "  M");
        }

        // place derived data, built from the last data instance in the loop
        /* place heating degree days and sky cover information */

        if (dailyData
                .getNumHeatMonth() != ParameterFormatClimate.MISSING_DEGREE_DAY) {
            context.put("ttm",
                    String.format("%6s", dailyData.getNumHeatMonth()));
        } else {
            context.put("ttm", String.format("%6s", "M"));
        }

        if (periodData.getNumFairDays() != ParameterFormatClimate.MISSING) {
            context.put("s03",
                    String.format("%2s", periodData.getNumFairDays()));
        } else {
            context.put("s03", " M");
        }

        if (pClimo
                .getNumHeatPeriodNorm() != ParameterFormatClimate.MISSING_DEGREE_DAY
                && dailyData
                        .getNumHeatMonth() != ParameterFormatClimate.MISSING_DEGREE_DAY) {
            int dptrheat = dailyData.getNumHeatMonth()
                    - pClimo.getNumHeatPeriodNorm();
            context.put("dfn", String.format("%6s", dptrheat));
        } else {
            context.put("dfn", String.format("%6s", "M"));
        }

        if (periodData
                .getNumPartlyCloudyDays() != ParameterFormatClimate.MISSING) {
            context.put("s47",
                    String.format("%2s", periodData.getNumPartlyCloudyDays()));
        } else {
            context.put("s47", " M");
        }

        if (periodData
                .getNumHeat1July() != ParameterFormatClimate.MISSING_DEGREE_DAY) {
            context.put("tfj",
                    String.format("%6s", periodData.getNumHeat1July()));
        } else {
            context.put("tfj", String.format("%6s", "M"));
        }

        if (periodData
                .getNumMostlyCloudyDays() != ParameterFormatClimate.MISSING) {
            context.put("s80",
                    String.format("%2s", periodData.getNumMostlyCloudyDays()));
        } else {
            context.put("s80", " M");
        }

        if (pClimo
                .getNumHeat1JulyNorm() != ParameterFormatClimate.MISSING_DEGREE_DAY
                && periodData
                        .getNumHeat1July() != ParameterFormatClimate.MISSING_DEGREE_DAY) {
            int dptrheat = periodData.getNumHeat1July()
                    - pClimo.getNumHeat1JulyNorm();
            context.put("dfn2", String.format("%6s", dptrheat));
        } else {
            context.put("dfn2", String.format("%6s", "M"));
        }

        /* place cooling degree days and pressure information the month */
        if (dailyData
                .getNumCoolMonth() != ParameterFormatClimate.MISSING_DEGREE_DAY) {
            context.put("ttm2",
                    String.format("%6s", dailyData.getNumCoolMonth()));
        } else {
            context.put("ttm2", String.format("%6s", "M"));
        }

        if (pClimo
                .getNumCoolPeriodNorm() != ParameterFormatClimate.MISSING_DEGREE_DAY
                && dailyData
                        .getNumCoolMonth() != ParameterFormatClimate.MISSING_DEGREE_DAY) {
            int dptrcool = dailyData.getNumCoolMonth()
                    - pClimo.getNumCoolPeriodNorm();
            context.put("dfn3", String.format("%6s", dptrcool));
        } else {
            context.put("dfn3", String.format("%6s", "M"));
        }

        if (periodData
                .getNumCool1Jan() != ParameterFormatClimate.MISSING_DEGREE_DAY) {
            context.put("tfj2",
                    String.format("%6s", periodData.getNumCool1Jan()));
        } else {
            context.put("tfj2", String.format("%6s", "M"));
        }

        /*
         * retrieve minimum and maximum sea-level pressure from ASOS monthly
         * summary message or dailyClimate
         */
        SLP slp = dailyClimateDao.retrieveMsmPrs(aDate, station);

        if (!ClimateUtilities.floatingEquals(slp.getMaxSLP(),
                ParameterFormatClimate.MISSING_SLP)) {
            context.put("maxslp", String.format("%5.2f", slp.getMaxSLP()));

            if (slp.getDayMaxSLP() != ParameterFormatClimate.MISSING_DATE) {
                context.put("daymaxslp",
                        String.format("%2s", slp.getDayMaxSLP()));
            } else {
                context.put("daymaxslp", "M");
            }
        } else {
            context.put("maxslp", "M");
            context.put("daymaxslp", "M");
        }

        if (pClimo
                .getNumCool1JanNorm() != ParameterFormatClimate.MISSING_DEGREE_DAY
                && periodData
                        .getNumCool1Jan() != ParameterFormatClimate.MISSING_DEGREE_DAY) {
            int dptrcool = periodData.getNumCool1Jan()
                    - pClimo.getNumCool1JanNorm();
            context.put("dfn4", String.format("%6s", dptrcool));
        } else {
            context.put("dfn4", String.format("%6s", "M"));
        }

        if (!ClimateUtilities.floatingEquals(slp.getMinSLP(),
                ParameterFormatClimate.MISSING_SLP)) {
            context.put("minslp", String.format("%5.2f", slp.getMinSLP()));

            if (slp.getDayMinSLP() != ParameterFormatClimate.MISSING_DATE) {
                context.put("dayminslp",
                        String.format("%2s", slp.getDayMinSLP()));
            } else {
                context.put("dayminslp", "M");
            }
        } else {
            context.put("minslp", "M");
            context.put("dayminslp", "M");
        }

        /*
         * Set remarks - prefix remarks with "#FINAL-MM-YY#" for completed
         * months.
         */
        ClimateDate currentDate = ClimateDate.getLocalDate();
        if (currentDate.getMon() == aDate.getMon()
                && currentDate.getYear() == aDate.getYear()) {
            // not final for month
            context.put("remarks", remarks);
        } else {
            // final for month
            SimpleDateFormat fmt = new SimpleDateFormat("MM-yy");
            String finalRemark = "#FINAL-"
                    + fmt.format(aDate.getCalendarFromClimateDate().getTime())
                    + "#";
            if (remarks != null && !remarks.isEmpty()) {
                // user made remarks
                context.put("remarks", finalRemark + "\n" + remarks);
            } else {
                // no user remarks
                context.put("remarks", finalRemark);
            }
        }

        /*
         * Parse and format to generate report.
         */
        Template vmTemp;
        try {
            vmTemp = ve.getTemplate(vmTempFile);

            /* now render the template into a Writer */
            StringWriter writer = new StringWriter();
            vmTemp.merge(context, writer);

            lines.add(writer.toString());

        } catch (ResourceNotFoundException ne) {
            logger.error("F6Builder could not find template file " + vmTempFile,
                    ne);
        } catch (ParseErrorException pe) {
            logger.error(
                    "F6Builder could not parse template file " + vmTempFile,
                    pe);
        } catch (MethodInvocationException me) {
            logger.error(
                    "F6Builder could not invoke template file " + vmTempFile,
                    me);
        }

        return lines;
    }

}