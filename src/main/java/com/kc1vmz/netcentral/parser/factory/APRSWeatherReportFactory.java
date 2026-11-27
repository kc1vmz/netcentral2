package com.kc1vmz.netcentral.parser.factory;

/*
    Net Central
    Copyright (c) 2025, 2026 John Rokicki KC1VMZ

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
    
    http://www.kc1vmz.com
*/

import java.time.ZonedDateTime;
import java.util.Arrays;

import org.slf4j.LoggerFactory;

import com.kc1vmz.netcentral.aprsobject.object.APRSWeatherReport;
import com.kc1vmz.netcentral.aprsobject.utils.CompressedDataFormatUtils;
import com.kc1vmz.netcentral.parser.exception.APRSTimeConversionException;
import com.kc1vmz.netcentral.parser.exception.ParserException;
import com.kc1vmz.netcentral.parser.util.APRSTime;
import com.kc1vmz.netcentral.parser.util.PacketIdentity;
import com.kc1vmz.netcentral.parser.util.StringUtils;

import org.slf4j.Logger;

public class APRSWeatherReportFactory {
    private static final Logger logger = LoggerFactory.getLogger(APRSWeatherReportFactory.class);

    public static APRSWeatherReport parse(byte [] header, byte [] data) throws ParserException {
        logger.debug("Parsing APRSWeatherReport object");
        APRSWeatherReport ret = new APRSWeatherReport();

        if (data == null || data.length == 0) {
            return null;
        }

        ret.setHeader(header);
        ret.setData(data);
        ret.setDti(data[0]);
        ret.setCallsignFrom(PacketIdentity.getCallsignFrom(header));

        if (data[0] == '_') {
            ret = parsePositionless(header, data, ret);
        } else if ((data[0] == '!') || (data[0] == '=')) {
            ret = parseCompleteWithoutTimestamp(header, data, ret);
        } else if ((data[0] == '/') || (data[0] == '@')) {
            ret = parseCompleteWithTimestamp(header, data, ret);
        }

        if (ret != null) {
            if (ret.getTime() == null) {
                ret.setTime("");
                ret.setLdtime(ZonedDateTime.now());
            }
        }

        return ret;
    }

    private static APRSWeatherReport parseCompleteWithTimestamp(byte[] header, byte[] data, APRSWeatherReport ret) {
        int messageIndex = 0;

        if (data.length < 21) return null;
        // time is the same in both
        byte [] time_bytes = Arrays.copyOfRange(data, 1, 8);
        ret.setTime(new String(time_bytes));
        try {
            ret.setLdtime(APRSTime.convertAPRSTimeToZonedDateTime(ret.getTime()));
        } catch (APRSTimeConversionException e) {
            logger.debug("Invalid weather timestamp from {}: {}", ret.getCallsignFrom(), ret.getTime());
        }

        if (data.length > 17 && data[17] == '_') {
            return parseCompleteWithTimestampCompressedLocation(header, data, ret);
        }

        if (data.length < 34) return null;
        // uncompressed location

        ret.setSymbolTableId(StringUtils.stringify(data[16]));
        ret.setSymbolTableCode(StringUtils.stringify(data[26]));

        byte [] lat = Arrays.copyOfRange(data, 8, 16);
        ret.setLat(new String(lat));
        byte [] lon = Arrays.copyOfRange(data, 17, 26);

        if ((lon[8] != 'E') && (lon[8] != 'W')) {
            lon = Arrays.copyOfRange(data, 17, 25);
            // bad lon - short one character
            String prependZero = "0"+new String(lon);
            lon = prependZero.getBytes();
            messageIndex = 26;
        } else {
            messageIndex = 27;
        }
        ret.setLon(new String(lon));

        byte [] directionAndSpeed_bytes = Arrays.copyOfRange(data, messageIndex, messageIndex+7);
        String directionAndSpeed = new String(directionAndSpeed_bytes);
        if (!parseWind(directionAndSpeed, ret)) return null;

        messageIndex += 7; // skip wind speed and direction

        byte [] weatherData_bytes = Arrays.copyOfRange(data, messageIndex, data.length);
        parseWeatherData(weatherData_bytes, ret);

        return ret;
    }

    private static APRSWeatherReport parseCompleteWithTimestampCompressedLocation(byte[] header, byte[] data, APRSWeatherReport ret) {
        // compressed location data
        byte [] compressedData = Arrays.copyOfRange(data, 9, 17);
        String lat = CompressedDataFormatUtils.formatLatitude(CompressedDataFormatUtils.getLatitudeShort(compressedData));
        String lon = CompressedDataFormatUtils.formatLongitude(CompressedDataFormatUtils.getLongitudeShort(compressedData));
        ret.setLat(lat);
        ret.setLon(lon);

        ret.setSymbolTableId(StringUtils.stringify(data[8]));
        ret.setSymbolTableCode(StringUtils.stringify(data[17]));

        // next is 7 bytes for wind speed/direction
        byte [] directionAndSpeed_bytes = Arrays.copyOfRange(data, 18, 21);
        String directionAndSpeed = new String(directionAndSpeed_bytes);
        ret = determineDirectionAndSpeedFromCompressed(ret, directionAndSpeed);

        byte [] weatherData_bytes = Arrays.copyOfRange(data, 21, data.length);
        parseWeatherData(weatherData_bytes, ret);

        return ret;
    }

    private static APRSWeatherReport parseCompleteWithoutTimestamp(byte[] header, byte[] data, APRSWeatherReport ret) {
        int messageIndex = 0;

        if (data.length < 14) return null;
        if (data[10] == '_') {
            return parseCompleteWithoutTimestampCompressedLocation(header, data, ret);
        }

        if (data.length < 26) return null;
        byte [] lat = Arrays.copyOfRange(data, 1, 9);
        ret.setLat(new String(lat));
        ret.setSymbolTableId(StringUtils.stringify(data[9]));
        byte [] lon = Arrays.copyOfRange(data, 10, 19);

        if ((lon[8] != 'E') && (lon[8] != 'W')) {
            lon = Arrays.copyOfRange(data, 10, 18);
            // bad lon - short one character
            String prependZero = "0"+new String(lon);
            lon = prependZero.getBytes();
            messageIndex = 18;
        } else {
            messageIndex = 19;
        }
        ret.setLon(new String(lon));

        if (data[messageIndex] != '_') {
            // this must be an underscore
            return null;
        }
        ret.setSymbolTableCode(StringUtils.stringify(data[messageIndex]));
        messageIndex++;

        // next is 7 bytes for wind speed/direction
        byte [] directionAndSpeed_bytes = Arrays.copyOfRange(data, messageIndex, messageIndex+7);
        String directionAndSpeed = new String(directionAndSpeed_bytes);
        if (!parseWind(directionAndSpeed, ret)) return null;
        messageIndex += 7;

        byte [] weatherData_bytes = Arrays.copyOfRange(data, messageIndex, data.length);
        parseWeatherData(weatherData_bytes, ret);

        return ret;
    }

    private static APRSWeatherReport parseCompleteWithoutTimestampCompressedLocation(byte[] header, byte[] data, APRSWeatherReport ret) {
        ret.setSymbolTableId(StringUtils.stringify(data[1]));
        ret.setSymbolTableCode(StringUtils.stringify(data[10]));

        byte [] compressedData = Arrays.copyOfRange(data, 2, 10);
        String lat = CompressedDataFormatUtils.formatLatitude(CompressedDataFormatUtils.getLatitudeShort(compressedData));
        String lon = CompressedDataFormatUtils.formatLongitude(CompressedDataFormatUtils.getLongitudeShort(compressedData));
        ret.setLat(new String(lat));
        ret.setLon(new String(lon));

        byte [] directionAndSpeed_bytes = Arrays.copyOfRange(data, 11, 14);
        String directionAndSpeed = new String(directionAndSpeed_bytes);
        ret = determineDirectionAndSpeedFromCompressed(ret, directionAndSpeed);

        byte [] weatherData_bytes = Arrays.copyOfRange(data, 14, data.length);
        parseWeatherData(weatherData_bytes, ret);

        return ret;
    }

    private static APRSWeatherReport determineDirectionAndSpeedFromCompressed(APRSWeatherReport ret, String directionAndSpeed) {

        if (directionAndSpeed.startsWith(" ")) {
            // ignore this value
            return ret;
        }

        char c = directionAndSpeed.charAt(0);
        if ((c >= '!') && (c <= 'z')) {
            // 0 - 89 after subtracting 33 from c
            // compressed course / speed
            int cInt = (int) c;
            cInt -= 33;

            ret.setWindDirection(cInt*4);

            char s = directionAndSpeed.charAt(1);
            int sInt = (int) s - 33;
            double base = 1.08;
            double result = Math.pow(base, sInt) - 1;
            ret.setWindSpeed((int) Math.round(result));
        }

        return ret;
    }

    private static APRSWeatherReport parsePositionless(byte[] header, byte[] data, APRSWeatherReport ret) {
        if (data.length < 9) return null;
        // positionless weather data
        byte [] time_bytes = Arrays.copyOfRange(data, 1, 9);
        ret.setTime(new String(time_bytes));
        try {
            ret.setLdtime(APRSTime.convertAPRSTimeToZonedDateTime(ret.getTime()));
        } catch (APRSTimeConversionException e) {
            logger.debug("Invalid weather timestamp from {}: {}", ret.getCallsignFrom(), ret.getTime());
        }

        byte [] weatherData_bytes = Arrays.copyOfRange(data, 9, data.length);
        parseWeatherData(weatherData_bytes, ret);

        return ret;
    }

    private static void parseWeatherData(byte[] bytes, APRSWeatherReport report) {
        String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        var seen = new java.util.HashSet<Character>();
        int index = 0;
        while (index < text.length()) {
            char field = text.charAt(index++);
            // Unknown text (including the w software/device suffix) terminates measurements.
            // Never search arbitrary comments for letters that resemble weather fields.
            if ("csgtrpPhbLl#".indexOf(field) < 0 || !seen.add(field)) break;
            int start = index;
            while (index < text.length() && (Character.isDigit(text.charAt(index))
                    || text.charAt(index) == '.' || text.charAt(index) == '-'
                    || (text.charAt(start) == ' ' && text.charAt(index) == ' '))) index++;
            String raw = text.substring(start, index);
            int width = field == 'b' ? 5 : field == 'h' ? 2 : 3;
            if (raw.length() == width && (raw.chars().allMatch(c -> c == '.')
                    || raw.chars().allMatch(c -> c == ' '))) continue;
            String pattern = field == 't' ? "(?:[0-9]{3}|-[0-9]{2})" : "[0-9]{" + width + "}";
            if (!raw.matches(pattern)) {
                logger.debug("Invalid weather field from {}: {}{}", report.getCallsignFrom(), field, raw);
                continue;
            }
            int value = Integer.parseInt(raw);
            switch (field) {
                case 'c' -> report.setWindDirection(value);
                case 's' -> report.setWindSpeed(value);
                case 'g' -> report.setGust(value);
                case 't' -> report.setTemperature(value);
                case 'r' -> report.setRainfallLast1Hr(value);
                case 'p' -> report.setRainfallLast24Hr(value);
                case 'P' -> report.setRainfallSinceMidnight(value);
                case 'h' -> report.setHumidity(value == 0 ? 100 : value);
                case 'b' -> report.setBarometricPressure(value);
                case 'L' -> report.setLuminosity(value);
                case 'l' -> report.setLuminosity(value + 1000);
                case '#' -> report.setRawRainCounter(value);
            }
        }
    }

    private static boolean parseWind(String wind, APRSWeatherReport report) {
        if (!wind.matches("[0-9. ]{3}/[0-9. ]{3}")) return false;
        if (wind.substring(0, 3).matches("[0-9]{3}"))
            report.setWindDirection(Integer.parseInt(wind.substring(0, 3)));
        if (wind.substring(4).matches("[0-9]{3}"))
            report.setWindSpeed(Integer.parseInt(wind.substring(4)));
        return true;
    }
}
