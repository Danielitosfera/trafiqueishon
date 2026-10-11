package trafiqueishon.simulacion;

import java.util.Set;

public enum TipoVehiculo {
    PARTICULAR("Particular", Set.of("motorway", "motorway_link", "trunk", "trunk_link",
            "primary", "primary_link", "secondary", "secondary_link", "tertiary",
            "tertiary_link", "unclassified", "residential", "living_street", "service", "road")),
    MOTO_LINEAL("Moto lineal", Set.of("motorway", "motorway_link", "trunk", "trunk_link",
            "primary", "primary_link", "secondary", "secondary_link", "tertiary",
            "tertiary_link", "unclassified", "residential", "living_street", "service", "road")),
    MOTOTAXI("Mototaxi", Set.of("trunk", "trunk_link", "primary", "primary_link",
            "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified",
            "residential", "living_street", "service", "road")),
    CARGA("Carga", Set.of("trunk", "trunk_link", "primary", "primary_link",
            "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified",
            "residential", "service", "road")),
    TRAILER("Trailer pesado", Set.of("trunk", "trunk_link", "primary", "primary_link",
            "secondary", "secondary_link")),
    MICRO("Micro", Set.of("trunk", "trunk_link", "primary", "primary_link",
            "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified",
            "residential", "living_street", "service", "road"));

    private final String etiqueta;
    private final Set<String> viasPermitidas;

    TipoVehiculo(String etiqueta, Set<String> viasPermitidas) {
        this.etiqueta = etiqueta;
        this.viasPermitidas = viasPermitidas;
    }

    public String etiqueta() { return etiqueta; }

    public Set<String> viasPermitidas() { return viasPermitidas; }

    public String viasPermitidasDescripcion() {
        return viasPermitidas.stream().map(TipoVehiculo::traducirVia).sorted()
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String traducirVia(String via) {
        return switch (via) {
            case "motorway" -> "autopista";
            case "motorway_link" -> "enlace de autopista";
            case "trunk" -> "vía troncal";
            case "trunk_link" -> "enlace troncal";
            case "primary" -> "vía principal";
            case "primary_link" -> "enlace principal";
            case "secondary" -> "vía secundaria";
            case "secondary_link" -> "enlace secundario";
            case "tertiary" -> "vía terciaria";
            case "tertiary_link" -> "enlace terciario";
            case "unclassified" -> "vía sin clasificar";
            case "residential" -> "vía residencial";
            case "living_street" -> "calle residencial";
            case "service" -> "vía de servicio";
            case "road" -> "carretera";
            default -> via;
        };
    }

    public boolean puedeCircular(String highway) {
        return viasPermitidas.contains(highway);
    }
}
