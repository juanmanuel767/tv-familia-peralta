package com.familia.tv;

/** Shared preference names used by the home screen and its phone remote. */
final class HomePrefs {
    static final String FILE = "familia";
    static final String CITY = "ciudad";
    static final String LAT = "latitud";
    static final String LON = "longitud";
    static final String MESSAGE = "mensaje_texto";
    static final String MESSAGE_TIME = "mensaje_hora";
    static final String WEATHER = "clima_texto";
    static final String FOTOS_VERSION = "fotos_version";
    static final String PC_IP = "pc_ip";
    static final String PC_PORT = "pc_puerto";
    static final String PC_KEY = "pc_clave";
    static final String PC_SEEN = "pc_visto";

    static final String DEFAULT_CITY = "Neiva";
    static final double DEFAULT_LAT = 2.9273;
    static final double DEFAULT_LON = -75.2819;

    private HomePrefs() { }
}
