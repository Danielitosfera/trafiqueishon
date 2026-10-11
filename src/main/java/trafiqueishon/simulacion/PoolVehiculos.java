package trafiqueishon.simulacion;

/**
 * Pool denso de slots para el almacenamiento structure-of-arrays del motor.
 * Los indices activos siempre estan en [0, activos), asi no se crean wrappers
 * Java por vehiculo ni se mantiene una lista de objetos de vida corta.
 */
public final class PoolVehiculos {
    private int activos;

    public int adquirir(int limite) {
        if (limite < 1 || activos >= limite) {
            throw new IllegalStateException("El pool de vehiculos alcanzo su capacidad.");
        }
        return activos++;
    }

    /** Devuelve el ultimo slot activo para que el cliente compacte sus arreglos. */
    public int liberar(int indice) {
        if (indice < 0 || indice >= activos) {
            throw new IndexOutOfBoundsException("Slot de vehiculo inactivo: " + indice);
        }
        return --activos;
    }

    public int activos() { return activos; }

    public void reiniciar() { activos = 0; }
}
