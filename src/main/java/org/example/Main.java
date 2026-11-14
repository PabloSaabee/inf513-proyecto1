package org.example;

import org.example.config.Config;
import org.example.correo.MonitorBuzon;
import org.example.datos.ConexionBD;
import org.example.interprete.Despachador;

/**
 * Sistema de Seguimiento a Titulados vía E-mail · Grupo 03 SA
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("=== Sistema de Seguimiento a Titulados · Grupo 03 SA ===");
        System.out.println("Correo        : " + Config.get("mail.direccion"));
        System.out.println("Base de datos : " + Config.get("db.url"));

        if (!ConexionBD.probar()) {
            System.err.println("Sin conexión a la base de datos. Revise config.properties.");
            return;
        }

        MonitorBuzon monitor = new MonitorBuzon(new Despachador());

        Runtime.getRuntime().addShutdownHook(new Thread(monitor::detener));

        monitor.iniciar();
    }
}