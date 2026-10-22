package trafiqueishon.simulacion;

/** Parada con su demanda agregada: pasajeros esperando y, de ellos, los que hacen transbordo. */
public record EberthMParadaEspera(double x, double y, int esperando, int transbordo) {}
