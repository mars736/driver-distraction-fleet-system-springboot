package common.util;

public final class GeoDistance {
    private static final double EARTH_RADIUS_KM = 6371.0088;

    private GeoDistance() { }

    public static double kilometers(double latitudeFrom, double longitudeFrom, double latitudeTo, double longitudeTo) {
        double from = Math.toRadians(latitudeFrom);
        double to = Math.toRadians(latitudeTo);
        double latHalf = Math.sin((to - from) / 2);
        double lonHalf = Math.sin(Math.toRadians(longitudeTo - longitudeFrom) / 2);
        double haversine = latHalf * latHalf + Math.cos(from) * Math.cos(to) * lonHalf * lonHalf;
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(Math.clamp(haversine, 0, 1)));
    }
}
