package trafiqueishon.ruta;

import java.util.ArrayList;
import java.util.List;

public final class LuisYGestorTrayectos {

    private final List<LuisYTrayecto> trayectos = new ArrayList<>();

    public void agregar(LuisYTrayecto t) {
        trayectos.removeIf(x -> x.id().equals(t.id()));
        trayectos.add(t);
    }

    public void eliminar(String id) {
        trayectos.removeIf(t -> t.id().equals(id));
    }

    public void limpiar() { trayectos.clear(); }

    public List<LuisYTrayecto> getTrayectos() { return List.copyOf(trayectos); }

    public LuisYTrayecto buscar(String id) {
        return trayectos.stream().filter(t -> t.id().equals(id)).findFirst().orElse(null);
    }
}