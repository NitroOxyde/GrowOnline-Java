import org.apache.commons.daemon.Daemon;
import org.apache.commons.daemon.DaemonContext;
import org.apache.commons.daemon.DaemonInitException;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class Main implements Daemon
{
    private Thread GOThread;
    private boolean stopped = false;

    @Override
    public void init(DaemonContext daemonContext) throws DaemonInitException, Exception {

        String[] args = daemonContext.getArguments();

        GOThread = new Thread()
        {
            private long lastTick = 0;

            @Override
            public synchronized void start()
            {
                Main.this.stopped = false;
                super.start();
            }

            @Override
            public void run()
            {
                Initialize();
                while(!stopped)
                {
                    long now = System.currentTimeMillis();
                    if(now - lastTick >= 5000){
                        tick();
                        lastTick = now;
                    }
                }
            }
        };
    }

    @Override
    public void start() throws Exception {
        GOThread.start();}

    @Override
    public void stop() throws Exception
    {
        stopped = true;
        try
        {
            GOThread.join(1000);
        }catch(InterruptedException e){Debug.println(e.getMessage());throw e;}
    }

    @Override
    public void destroy() {GOThread = null;}

    public static void Initialize()
    {
        System.out.println("   _____                    ____        _ _            \n" +
                "  / ____|                  / __ \\      | (_)           \n" +
                " | |  __ _ __ _____      _| |  | |_ __ | |_ _ __   ___ \n" +
                " | | |_ | '__/ _ \\ \\ /\\ / / |  | | '_ \\| | | '_ \\ / _ \\\n" +
                " | |__| | | | (_) \\ V  V /| |__| | | | | | | | | |  __/\n" +
                "  \\_____|_|  \\___/ \\_/\\_/  \\____/|_| |_|_|_|_| |_|\\___|\n" +
                "                                                       ");

        System.out.println("Récupération de la config");
        try{
            InputStream ips=new FileInputStream("/var/www/api/config.php");
            InputStreamReader ipsr=new InputStreamReader(ips);
            BufferedReader br=new BufferedReader(ipsr);
            String line;
            while ((line=br.readLine())!=null){
                if(line.contains("configHostBdd")){var.db_host=line.split("\"")[1];}
                if(line.contains("configNameBdd")){var.db_name=line.split("\"")[1];}
                if(line.contains("configUserBdd")){var.db_username=line.split("\"")[1];}
                if(line.contains("configPassBdd")){var.db_psswd=line.split("\"")[1];}
                if(line.contains("lampPin")){var.lamp=new Component(Integer.parseInt(line.split("=")[1].replace(";","")));}
                if(line.contains("fanPin")){var.fan=new Component(Integer.parseInt(line.split("=")[1].replace(";","")));}
                if(line.contains("pumpPin")){var.pump=new Component(Integer.parseInt(line.split("=")[1].replace(";","")));}
                if(line.contains("heaterPin")){var.heater=new Component(Integer.parseInt(line.split("=")[1].replace(";","")));}
                if(line.contains("foggerPin")){var.fogger=new Component(Integer.parseInt(line.split("=")[1].replace(";","")));}
            }
            br.close();
        }
        catch (Exception e){
            System.out.println(e.toString());
        }
        Debug.println("Initialisation des entrées/sorties");
        System_Function.exec("/home/wiringPi/gpio/gpio mode "+var.lamp.getPin()+" out");
        System_Function.exec("/home/wiringPi/gpio/gpio mode "+var.fan.getPin()+" out");
        System_Function.exec("/home/wiringPi/gpio/gpio mode "+var.pump.getPin()+" out");
        System_Function.exec("/home/wiringPi/gpio/gpio mode "+var.heater.getPin()+" out");
        //System_Function.exec("gpio mode "+var.sonde+" in");//]______trucs un peut spécial avec les pins
        System_Function.exec("/home/wiringPi/gpio/gpio mode 7 in");            // ]
        var.lamp.set(false);
        var.fan.set(false);
        var.pump.set(false);
        var.heater.set(false);
        if(!System_Function.checkDBExists(var.db_name))//si la BDD existe pas on la crée
        {
            Debug.println("BDD inexistante, création");
            System_Function.createDB();
        }
        Environnement.startThread();//on démarre l'écriture de la température et de l'humidité dans la bdd
        SMS.startThread();//On démarre le service qui vérifiera la temperature et l'humidité et enverra des sms si il y a un problème ( 27°C au lieu de 25 par exemple )
    }

    public static void tick()//fonction qui gèrera tout et qui sera éxécutée toute les x secondes
    {
        Debug.println("Récuperation du profil selectionné");
        System_Function.getProfile();
        if(Status.Name==null||System_Function.getCurrentProfile().equals(""))
        {
            Debug.println("Aucun profil choisi, abandon du tick");
            return;
        }
        if(System_Function.isDay())//si on est le jour
        {
            //Debug.println("JOUR");
            if(!var.lamp.getState()){var.lamp.set(true);Debug.println("Allumage de la lampe");}
            if(!var.fan.getState()){var.fan.set(true);Debug.println("Allumage du ventilateur");}
        }
        else//si on est la nuit
        {
            //Debug.println("NUIT");
            if(var.lamp.getState()){var.lamp.set(false);Debug.println("Extinction de la lampe");}
            if(System_Function.isFanOn()&&!var.fan.getState()){var.fan.set(true);Debug.println("Allumage du ventilateur");}
            else if(var.fan.getState()) {var.fan.set(false);Debug.println("Extinction du ventilateur");}
        }
        /////////////////////////////////////////////////
        //ARROSAGE
        //

        Date now = new Date();
        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss");
        try {now = format.parse(format.format(now));} catch (ParseException e) {e.printStackTrace();}//on récupère uniquement les heures et les minutes
        if(Status.Water_Amount!=0&&System_Function.isWaterDay()&&now.after(Status.Watering_Hour)&&now.before(new Date(Status.Watering_Hour.getTime()+(long)(Status.Water_Amount/Status.Pump_Flow*60000))))
        {
            Debug.println("Arrosage en cours");
            if(!var.pump.getState()){var.pump.set(true);}
        }
        else {if(var.pump.getState()){var.pump.set(false);Debug.println("Fin de l'arrosage");}}

        /////////////////////////////////////////////////
        //REGULATION DE LA TEMPERATURE
        //
        if(Status.LastTemp<Status.Temperature)
        {
            Debug.println("Température inférieure à la température souhaitée");
            if(!var.heater.getState()){var.heater.set(true);Debug.println("Allumage de la résistance chauffante");}
        }
        if(Status.LastTemp>=Status.Temperature)
        {
            Debug.println("Température supérieure ou égale à la température souhaitée");
            if(var.heater.getState()){var.heater.set(false);Debug.println("Extinction de la résistance chauffante");}
        }

    }
}
