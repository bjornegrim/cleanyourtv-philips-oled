package com.gathin.tvhome;

/** The line icon for an Open-Meteo WMO weather code (https://open-meteo.com/en/docs, "WMO Weather interpretation codes"). */
final class WeatherIcon {
    private WeatherIcon() {
    }

    /** A drawable resource for the code, the sun or moon by is_day; 0 for -1 (no reading yet). Unknown codes: a cloud. */
    static int forCode(int code, boolean day) {
        switch (code) {
            case -1: return 0;
            case 0: case 1: return day ? R.drawable.ic_weather_sun : R.drawable.ic_weather_moon;
            case 2: return day ? R.drawable.ic_weather_partly_day : R.drawable.ic_weather_partly_night;
            case 3: return R.drawable.ic_weather_cloud;
            case 45: case 48: return R.drawable.ic_weather_fog;
            case 51: case 53: case 55: case 56: case 57: return R.drawable.ic_weather_drizzle;
            case 61: case 63: case 65: case 66: case 67: case 80: case 81: case 82: return R.drawable.ic_weather_rain;
            case 71: case 73: case 75: case 77: case 85: case 86: return R.drawable.ic_weather_snow;
            case 95: case 96: case 99: return R.drawable.ic_weather_thunder;
            default: return R.drawable.ic_weather_cloud;
        }
    }
}
