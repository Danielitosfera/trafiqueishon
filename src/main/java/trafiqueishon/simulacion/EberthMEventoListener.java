package trafiqueishon.simulacion;

import java.util.List;

@FunctionalInterface
public interface EberthMEventoListener {
    void eventosActualizados(List<EberthMEventoTrafico> eventos);
}
