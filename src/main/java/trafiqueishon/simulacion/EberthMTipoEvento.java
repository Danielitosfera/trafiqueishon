package trafiqueishon.simulacion;

public enum EberthMTipoEvento {
    TRANCADERA("Trancadera", 0.1),
    VIA_CERRADA("Vía cerrada", 0.0),
    VENDEDORES_AMBULANTES("Vendedores ambulantes", 0.5),
    LLUVIA("Lluvia", 0.7),
    ACCIDENTE("Accidente", 0.0),
    GASOLINERA("Gasolinera", 1.0),
    SEMAFORO_ROTO("Semáforo roto", 1.0),
    MANIFESTACION("Venteo / manifestación", 0.0),
    /** Solo lo genera la simulación (cola > 10 vehículos en una vía); no se coloca a mano. */
    POLICIA_TRANSITO("Policía de tránsito", 1.0);

    /** Radio de la zona de lluvia alrededor del clic. */
    public static final double RADIO_LLUVIA_METROS = 1_000;

    /** Multiplicador de la demanda de pasajeros de las paradas cercanas a vendedores ambulantes. */
    public static final double MULTIPLICADOR_DEMANDA_VENDEDORES = 3.0;

    private final String etiqueta;
    private final double velocidad;

    EberthMTipoEvento(String etiqueta, double velocidad) {
        this.etiqueta = etiqueta;
        this.velocidad = velocidad;
    }

    /** Fracción de la velocidad normal que queda en los tramos afectados (0 = bloqueado). */
    public double multiplicadorVelocidad() {
        return velocidad;
    }

    /** Cierra por completo el tramo (velocidad 0). */
    public boolean bloqueaVia() {
        return this == VIA_CERRADA || this == ACCIDENTE;
    }

    /** Reduce la velocidad solo del tramo más cercano al clic (y su sentido contrario). */
    public boolean ralentizaTramo() {
        return this == TRANCADERA || this == VENDEDORES_AMBULANTES;
    }

    /** Bloquea el nodo más cercano y todos sus tramos adyacentes. */
    public boolean bloqueaNodo() {
        return this == MANIFESTACION;
    }

    /** Afecta solo a los vehículos dentro de un radio fijo alrededor del clic. */
    public boolean esZonal() {
        return this == LLUVIA;
    }

    /** Afecta a la intersección más cercana, no a los tramos. */
    public boolean afectaNodo() {
        return this == SEMAFORO_ROTO;
    }

    /** Policía agilizando una intersección: la luz roja de su semáforo dura la mitad. */
    public boolean esPolicia() {
        return this == POLICIA_TRANSITO;
    }

    @Override
    public String toString() {
        return etiqueta;
    }
}
