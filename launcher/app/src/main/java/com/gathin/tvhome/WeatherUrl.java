package com.gathin.tvhome;

final class WeatherUrl {
    private WeatherUrl() {}

    /** The request URL for a config.xml latitude and longitude, or null when either is empty, not a number or out of range. */
    static String of(String latitude, String longitude) {
        try {
            double lat = Double.parseDouble(latitude.trim());
            double lon = Double.parseDouble(longitude.trim());
            if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180)) return null;   // also rejects NaN
            return "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                    + "&current=temperature_2m,apparent_temperature,weather_code&timezone=auto";
        } catch (RuntimeException e) {
            return null;
        }
    }
}
