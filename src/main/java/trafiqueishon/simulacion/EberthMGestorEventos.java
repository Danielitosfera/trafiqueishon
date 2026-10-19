package trafiqueishon.simulacion;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class EberthMGestorEventos {
    private static final Logger LOG = Logger.getLogger(EberthMGestorEventos.class.getName());
    private final List<EberthMEventoListener> listeners = new CopyOnWriteArrayList<>();

    public void agregarListener(EberthMEventoListener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void quitarListener(EberthMEventoListener listener) {
        listeners.remove(listener);
    }

    public void publicar(List<EberthMEventoTrafico> eventos) {
        List<EberthMEventoTrafico> copia = List.copyOf(eventos);
        for (EberthMEventoListener listener : listeners) {
            try {
                listener.eventosActualizados(copia);
            } catch (RuntimeException ex) {
                LOG.log(Level.SEVERE, "Un listener no pudo procesar el cambio de eventos", ex);
            }
        }
    }
}
