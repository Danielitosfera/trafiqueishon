package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;
import trafiqueishon.geo.PieroGEdge;
import trafiqueishon.geo.PieroGNodo;
import trafiqueishon.ruta.LuisYPuntoImportante;
import trafiqueishon.ruta.LuisYTrayecto;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EberthMMotorSimulacionTest {

    @Test
    void lasOrdenesSeEjecutanEnElHiloDelMotorYPublicanUnaFoto() throws Exception {
        try (EberthMMotorSimulacion motor = new EberthMMotorSimulacion(new EberthMSimuladorBuses(), false)) {
            AtomicReference<String> hilo = new AtomicReference<>();
            motor.ejecutar(s -> {
                hilo.set(Thread.currentThread().getName());
                s.iniciar(trayecto(), 3);
            }).get(5, TimeUnit.SECONDS);

            assertEquals("motor-simulacion", hilo.get());
            EberthMMotorSimulacion.Instantanea foto = motor.instantanea();
            assertEquals(3, foto.vehiculos().size());
            assertTrue(foto.ejecutando());
            assertTrue(foto.haySimulacion());

            double x0 = foto.vehiculos().getFirst().x();
            motor.avanzarManual(0.5).get(5, TimeUnit.SECONDS);
            EberthMMotorSimulacion.Instantanea despues = motor.instantanea();
            assertTrue(despues.version() > foto.version());
            assertTrue(despues.vehiculos().getFirst().x() > x0);
            // La foto anterior es inmutable: no cambia al avanzar.
            assertEquals(x0, foto.vehiculos().getFirst().x(), 0.0);
        }
    }

    @Test
    void elRelojInternoAvanzaSinIntervencionDeLaInterfaz() throws Exception {
        try (EberthMMotorSimulacion motor = new EberthMMotorSimulacion()) {
            motor.ejecutar(s -> s.iniciar(trayecto(), 1)).get(5, TimeUnit.SECONDS);
            long version = motor.instantanea().version();
            long limite = System.currentTimeMillis() + 3_000;
            while (motor.instantanea().version() <= version + 3
                    && System.currentTimeMillis() < limite) {
                Thread.onSpinWait();
            }
            assertTrue(motor.instantanea().version() > version + 3,
                    "el motor debe publicar pasos por su cuenta");
            assertTrue(motor.instantanea().segundosSimulados() > 0);
        }
    }

    @Test
    void unaOrdenFallidaNoDetieneElMotor() throws Exception {
        try (EberthMMotorSimulacion motor = new EberthMMotorSimulacion(new EberthMSimuladorBuses(), false)) {
            var fallo = motor.ejecutar(s -> { throw new IllegalStateException("fallo"); });
            assertThrows(Exception.class, () -> fallo.get(5, TimeUnit.SECONDS));
            assertEquals(7, motor.calcular(s -> 7).get(5, TimeUnit.SECONDS));
        }
    }

    private LuisYTrayecto trayecto() {
        PieroGNodo inicio = new PieroGNodo(0, 0, 0, -70, -15);
        PieroGNodo fin = new PieroGNodo(1, 1, 0, -69.999, -15);
        PieroGEdge edge = new PieroGEdge(0, inicio, fin, 100, "residential",
                "Avenida", 30, false, 1, null);
        return new LuisYTrayecto("t1", "Ruta", "Empresa",
                List.of(new LuisYPuntoImportante("p1", "Inicio", -70, -15),
                        new LuisYPuntoImportante("p2", "Fin", -69.999, -15)),
                List.of(edge), 100, edge.costoBase, "PARTIDA");
    }
}
