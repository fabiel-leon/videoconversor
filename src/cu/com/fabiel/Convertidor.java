/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package cu.com.fabiel;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import static java.nio.file.LinkOption.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import static java.nio.file.StandardWatchEventKinds.*;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.swing.ImageIcon;

/**
 *
 * @author Fabiel
 */
public class Convertidor {

    private static void playSound(String file) {
        AudioInputStream audioInputStream = null;
        try {
            audioInputStream = AudioSystem.getAudioInputStream(new File(file));
            Clip clip = AudioSystem.getClip();
            clip.open(audioInputStream);
            clip.start();
        } catch (LineUnavailableException ex) {
            Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
        } catch (UnsupportedAudioFileException ex) {
            Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
        } finally {
            try {
                audioInputStream.close();
            } catch (IOException ex) {
                Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }

    private static class DirectoryObserver {

        private final WatchService watcher;
        private final LinkedBlockingDeque<Path> aconvertir;
        private final HashMap<Path, Boolean> carpetasObser;
        private final LinkedBlockingDeque<Path> convertidos;

        /**
         *
         * @param aconvertir
         * @throws IOException
         */
        public DirectoryObserver(LinkedBlockingDeque<Path> aconvertir, LinkedBlockingDeque<Path> convertidos) throws IOException {
            this.aconvertir = aconvertir;
            this.convertidos = convertidos;
            carpetasObser = new HashMap<Path, Boolean>();
            this.watcher = FileSystems.getDefault().newWatchService();
            //convertidos
            //carpetas observadas
            String line;
            FileReader fis = new FileReader("carpetas.txt");
            BufferedReader fol = new BufferedReader(fis);
            while ((line = fol.readLine()) != null) {
                String[] split = line.split("\"");
                Path p = Paths.get(split[0]);
                if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                    boolean recursive = Boolean.parseBoolean(split[1]);
                    if (recursive) {
                        registrarTodos(p);
                    } else {
                        registrarPath(p);
                        carpetasObser.put(p, false);
                    }
                }
            }
            fol.close();
            fis.close();
        }

        private void registrarPath(final Path p) throws IOException {
            p.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
            Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.getParent().endsWith(p)) {
                        registrarArchivo(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        private void registrarTodos(Path p) throws IOException {
            Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    registrarPath(dir);
                    carpetasObser.put(dir, Boolean.TRUE);
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        public void procesarEventos() throws IOException {
            if (carpetasObser.isEmpty()) {
//                System.out.println("No hay carpetas que observar");
                System.exit(-1);
            }
            while (true) {
                WatchKey key;
                try {
                    key = watcher.take();
                } catch (InterruptedException x) {
                    return;
                }
                for (Iterator<WatchEvent<?>> it = key.pollEvents().iterator(); it.hasNext();) {
                    WatchEvent<?> event = it.next();

                    WatchEvent.Kind kind = event.kind();
                    if (kind == OVERFLOW) {
                        continue;
                    }
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path name = ev.context();
                    System.out.println("name = " + name);
                    Path father = (Path) key.watchable();
                    Path child = father.resolve(name);
                    Boolean recursivo = carpetasObser.get(father);
                    if (kind == ENTRY_CREATE) {
                        if (Files.isDirectory(child, NOFOLLOW_LINKS)) {
                            if (recursivo) {
                                registrarTodos(child);
                            }
                        } else if (Files.isRegularFile(child, NOFOLLOW_LINKS)) {
                            registrarArchivo(child);
                        }
                    }
                }
                key.reset();
            }
        }

        private void registrarArchivo(Path child) throws IOException {
            String ct = Files.probeContentType(child);
            if (ct != null
                    && (ct.startsWith("video") || ct.endsWith("vnd.rn-realmedia-vbr") || ct.endsWith("vnd.rn-realmedia"))
                    && !convertidos.contains(child)) {
                aconvertir.add(child);
            }
        }
    }

    private static class Converter implements Runnable {

        private final LinkedBlockingDeque<Path> aconvertir;
        private final LinkedBlockingDeque<Path> convertidos;
        private String ubicacion;
        private String comandoI;
        PrintWriter pw;
        private long espAho;
        private long tiempoT;
        private int cnt;
        private TrayIcon ti;
        ArrayList<Path> inco = new ArrayList<Path>();
        private long tiempoDormidoT;

        //<editor-fold defaultstate="collapsed" desc="constructor">
        public Converter(LinkedBlockingDeque<Path> aconvertir,
                LinkedBlockingDeque<Path> convertidos,
                long espa, long tiempoT, int cnt, long tiempoDormido) {
//            FileReader incode = null;
//            try {
//                incode = new FileReader("inconvertibles.txt");
//            } catch (FileNotFoundException ex) {
//                Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
//            }
//            String line;
//            BufferedReader re = new BufferedReader(incode);
//            try {
//                while ((line = re.readLine()) != null) {
//                    inco.add(Paths.get(line));
//                }
//                re.close();
//                incode.close();
//            } catch (IOException ex) {
//                Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
//            }
            this.aconvertir = aconvertir;
            this.convertidos = convertidos;
            FileReader fr;
            try {
                fr = new FileReader("comando.txt");
                BufferedReader br = new BufferedReader(fr);
                for (int i = 0; i < 2; i++) {
                    switch (i) {
                        case 0:
                            comandoI = br.readLine();
                            break;
                        case 1:
                            ubicacion = br.readLine();
                            break;
                        default:
                            throw new AssertionError();
                    }
                }
                br.close();
                fr.close();
            } catch (IOException ex) {
                Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
            }
            FileWriter fw = null;
            try {
                fw = new FileWriter("convertidos.txt", true);
            } catch (IOException ex) {
                Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
            }
            pw = new PrintWriter(fw, true);
            this.espAho = espa;
            this.tiempoT = tiempoT;
            this.cnt = cnt;
            if (SystemTray.isSupported()) {
                SystemTray systemTray = SystemTray.getSystemTray();
                Dimension d = systemTray.getTrayIconSize();
                ti = new TrayIcon(new ImageIcon(getClass().getResource("wwork.png"))
                        .getImage(), "Sin Trabajo");
                //escalar imagen icon .getScaledInstance(d.width, d.height, Image.SCALE_SMOOTH)
                ti.setImageAutoSize(true);
                try {
                    systemTray.add(ti);
                } catch (AWTException ex) {
                    Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
            this.tiempoDormidoT = tiempoDormido;
        }
        //</editor-fold>

        @Override
        //<editor-fold defaultstate="collapsed" desc="run">
        public void run() {
            ti.displayMessage("Comenzando Conversión",
                    "Tiempo Prom Total: " + (cnt > 0 ? TimeUnit.MILLISECONDS.toHours((long) (tiempoT - tiempoDormidoT / (long) cnt)) : 0) + "min\n"
                    + "Espacio Total ahorrado: " + getMedidaCorrecta(espAho), TrayIcon.MessageType.INFO);
            inconvertible:
            while (true) {
                try {
                    sintrabajo();
                    leerComando();
                    Path file = aconvertir.take();
                    //                    System.out.println("cogi trabajo");
                    if (Files.exists(file, NOFOLLOW_LINKS) && !convertidos.contains(file) && !inco.contains(file)) {
                        nameError:
                        while (true) {
                            Path newF = file.getParent().resolve(file.getFileName() + "[h264].avi");
                            indicarConversion(file);
                            Date date = new Date();
                            long sizeF = Files.size(file);
                            long sizeN = 0;
                            Process process = Runtime.getRuntime().exec(ubicacion + comandoI + "\"" + file + "\" -o \"" + newF + "\"");//cmd = smbpasswd -r uci.cu -U tuUsuario
                            final InputStream in = process.getInputStream();
                            //<editor-fold defaultstate="collapsed" desc="thread paralizador">
                            ThreadImpl th = new ThreadImpl("StreamError", in);
                            th.start();
                            //</editor-fold>
                            String linea;
                            long time = 0;
                            final InputStream errorr = process.getErrorStream();
                            InputStreamReader isr = new InputStreamReader(errorr);
                            BufferedReader br = new BufferedReader(isr);
                            while ((linea = br.readLine()) != null) {
                                //                                System.out.println("linea = " + linea);
                                //                                System.out.println("file = " + file);
                                if (linea.startsWith("============ Sorry, this file format is not recognized/supported =============")) {
                                    process.destroy();
                                    process.waitFor();
                                    ti.displayMessage("Archivo Incodificable",
                                            "Archivo: " + file,
                                            TrayIcon.MessageType.ERROR);
                                    inco.add(file);
                                    FileWriter fw = new FileWriter("inconvertibles.txt", true);
                                    PrintWriter printer = new PrintWriter(fw);
                                    printer.println(file);
                                    printer.close();
                                    fw.close();
                                    br.close();
                                    isr.close();
                                    errorr.close();
                                    System.gc();
                                    continue inconvertible;
                                } else if (linea.startsWith("File not found:")) {
                                    process.destroy();
                                    process.waitFor();
                                    Path move = file.getParent().resolve(file.getFileName().toString().replace(" ", "-"));
                                    Files.move(file, move, StandardCopyOption.REPLACE_EXISTING);
                                    file = move;
                                    br.close();
                                    isr.close();
                                    errorr.close();
                                    System.gc();
                                    continue nameError;
                                }
                                if (Files.exists(newF, NOFOLLOW_LINKS)) {
                                    sizeN = Files.size(newF);
                                    if (sizeN >= sizeF) {
                                        process.destroy();
                                        process.waitFor();
                                        time = new Date().getTime() - date.getTime();
                                        Files.delete(newF);
                                        convertidos.add(file);
                                        pw.println(file + "\"" + sizeN + "\"" + time + "\"" + th.tiempoDormidoT);
                                        ti.displayMessage("Archivo Convertido Mayor",
                                                "Archivo: " + file + "\n"
                                                + "Tiempo Total: " + TimeUnit.MILLISECONDS.toSeconds(time) + "segs\n"
                                                + "Tiempo Sleep: " + TimeUnit.MILLISECONDS.toSeconds(th.tiempoDormidoT) + "segs\n"
                                                + "Tiempo Trabajo: " + TimeUnit.MILLISECONDS.toSeconds(time - th.tiempoDormidoT) + "segs", TrayIcon.MessageType.WARNING);
                                    }
                                }
                            }
                            br.close();
                            isr.close();
                            errorr.close();
                            process.waitFor();
                            if (sizeF > sizeN) {
                                Files.delete(file);
                                convertidos.add(newF);
                                time = new Date().getTime() - date.getTime();
                                pw.println(newF + "\"" + sizeF + "\"" + time + "\"" + th.tiempoDormidoT);
                                espAho += sizeF - sizeN;
                                ti.displayMessage("Conversión Exitosa",
                                        "Archivo: " + file + "\n"
                                        + "Tiempo: " + TimeUnit.MILLISECONDS.toSeconds(time) + "segs\n"
                                        + "Tiempo Sleep: " + TimeUnit.MILLISECONDS.toSeconds(th.tiempoDormidoT) + "segs\n"
                                        + "Tiempo Final: " + TimeUnit.MILLISECONDS.toSeconds(time - th.tiempoDormidoT) + "segs\n"
                                        + "Espacio ahorrado: " + getMedidaCorrecta(sizeF - sizeN) + "\n"
                                        + "Espacio Total ahorrado: " + getMedidaCorrecta(espAho), TrayIcon.MessageType.INFO);
                            }
                            tiempoDormidoT += th.tiempoDormidoT;
                            tiempoT += time;
                            cnt++;
                            aconvertir.remove(newF);
                            playSound("f.wav");
                            pw.flush();
                            System.gc();
                            //    esperarTiempo();
                            //                       System.out.println("termine el trabajo");
                            continue inconvertible;
                        }
                    }
                } catch (InterruptedException ex) {
                    Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
                } catch (IOException ex) {
                    Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
        //</editor-fold>

        //<editor-fold defaultstate="collapsed" desc="convirtiendo">
        private void indicarConversion(Path file) throws IOException {
            if (ti != null) {
                imageTrabajando();
                ti.setToolTip("Convirtiendo: " + file + "\n" // 
                        + "Espacio Ahorrado: " + getMedidaCorrecta(espAho) + "\n" //                        + "Tiempo Promedio: "
                        //                        + (cnt > 0 ? TimeUnit.MILLISECONDS.toSeconds((long) (tiempoT - tiempoDormidoT / (long) cnt)) : 0) + "seg"
                        );
            }
        }
        //</editor-fold>

        //<editor-fold defaultstate="collapsed" desc="medida correcta">
        private String getMedidaCorrecta(long l) {
            String medida = "B";
            long temp = l;
            byte cntd = 0;
            afuera:
            while (temp > 1024) {
                cntd++;
                temp = (long) (temp / (long) 1024);
                switch (cntd) {
                    case 1:
                        medida = "KB";
                        break;
                    case 2:
                        medida = "MB";
                        break;
                    case 3:
                        medida = "GB";
                        break;
                    case 4:
                        medida = "TB";
                        break afuera;
                }
            }
            return temp + medida;
        }
        //</editor-fold>

        //<editor-fold defaultstate="collapsed" desc="refrescando la pc">
        private void esperarTiempo() {
            ti.setToolTip("Refrescando El Micro");
            try {
                int t = 20;
                int te = t;
                int paso = 1;
                while (te > 0) {
                    try {
                        BufferedImage s = ImageIO.read(getClass().getResource("refresh.png"));
                        Graphics2D g = s.createGraphics();
                        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                        g.setFont(new Font("Arial", Font.BOLD, 12));
                        if (te >= (3 * t / 4)) {
                            g.setColor(Color.RED);
                        } else if (te >= (t * 2 / 4)) {
                            g.setColor(Color.ORANGE);
                        } else if (te >= (t / 4)) {
                            g.setColor(Color.YELLOW);
                        } else {
                            g.setColor(Color.WHITE);
                        }
                        if (te < 10) {
                            g.drawString(" " + te, 1, 11);
                        } else {
                            g.drawString("" + te, 1, 11);
                        }
                        ti.setImage(s);
                        TimeUnit.SECONDS.sleep(paso);
                        te += -paso;
                    } catch (InterruptedException ex) {
                        Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            } catch (IOException ex) {
                Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        //</editor-fold>

        private Image getTrayIconImage(String image) {
            return new ImageIcon(getClass().getResource(image)).getImage();
        }

        private void setTrayIconImage(String image) {
            ti.setImage(getTrayIconImage(image));
        }

        private void imageTrabajando() {
            setTrayIconImage("trayicon.png");
        }

        private void imageEsperando() {
            setTrayIconImage("refresh.png");
        }

        private void imageWWork() {
            setTrayIconImage("wwork.png");
        }

        private void sintrabajo() {
            imageWWork();
            ti.setToolTip("Sin Trabajo\n" + ""
                    + "Espacio Ahorrado: " + getMedidaCorrecta(espAho));
        }

        private void imageSi() {
            setTrayIconImage("sy.png");
        }

        private void imageNo() {
            setTrayIconImage("syn.png");
        }
        //<editor-fold defaultstate="collapsed" desc="leer comando">

        private void leerComando() {
            try {
                FileReader fr;
                fr = new FileReader("comando.txt");
                BufferedReader br = new BufferedReader(fr);
                comandoI = br.readLine();
                br.close();
                fr.close();
            } catch (IOException ex) {
                Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        //</editor-fold>

        //<editor-fold defaultstate="collapsed" desc="thread paralizador">
        private class ThreadImpl extends Thread {

            private final InputStream in;
            private long tiempoDormidoT;
            private long siesta;

            public ThreadImpl(String streamError, InputStream in) {
                super(streamError);
                this.in = in;
                this.tiempoDormidoT = 0;
                FileReader fr = null;
                try {
                    fr = new FileReader("sleep.txt");
                } catch (FileNotFoundException ex) {
                    Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
                }
                BufferedReader br = new BufferedReader(fr);
                try {
                    String readLine = br.readLine();
                    siesta = Long.parseLong(readLine);
                    br.close();
                    fr.close();
                } catch (IOException ex) {
                    Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
                }

            }

            @Override
            public void run() {
                try {
                    boolean cont = true;
                    InputStreamReader isr = new InputStreamReader(in);
                    BufferedReader leer = new BufferedReader(isr);
                    while (leer.readLine() != null) {
                        if (cont) {
                            imageSi();
                            cont = false;
                        } else {
                            imageNo();
                            cont = true;
                        }
                        try {
                            tiempoDormidoT += siesta;
                            //                            System.out.println("tiempoDormido = " + tiempoDormido);
                            TimeUnit.MILLISECONDS.sleep(siesta);
                        } catch (InterruptedException ex) {
                            Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
                        }
                    }
                    leer.close();
                    isr.close();
                    in.close();
                } catch (IOException ex) {
                }
            }
        }
        //</editor-fold>
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        if (args.length == 0) {//cuando no se pase parametros consideralo como la primera vez que se 
        }
        FileHandler fh = null;
        try {
            fh = new FileHandler("convertidor.log", true);
        } catch (IOException ex) {
            Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
        } catch (SecurityException ex) {
            Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
        }
        Logger.getLogger("").addHandler(fh);
        Preferences p = Preferences.systemRoot().node("/cu/com/fabiel/Convertidor");

//playSound("r.wav");
        LinkedBlockingDeque<Path> convertidos = new LinkedBlockingDeque<Path>();
        FileReader fr = null;
        try {
            fr = new FileReader("convertidos.txt");
        } catch (FileNotFoundException ex) {
            Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
        }
        BufferedReader conv = new BufferedReader(fr);
        String line;
        long espacioAhorrado = 0;
        long tiempototal = 0;
        long tiempoDormido = 0;
        int cont = 0;
//        try {
//            while ((line = conv.readLine()) != null) {
//                String[] split = line.split("\"");
//                Path get = Paths.get(split[0]);
//                if (Files.exists(get, LinkOption.NOFOLLOW_LINKS)) {
//                    convertidos.add(get);
//                    if (split.length >= 3) {
//                        try {
//                            cont++;
//                            espacioAhorrado += (Long.parseLong(split[1]) - Files.size(get));
//                            tiempototal += (Long.parseLong(split[2]));
//                        } catch (IOException ex) {
//                            Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
//                            continue;
//                        }
//                    }
//                    if (split.length == 4) {
//                        tiempoDormido += Long.parseLong(split[3]);
//                    }
//                }
//           }
//        } catch (IOException ex) {
//            Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
//        }
//        try {
//            conv.close();
//        } catch (IOException ex) {
//            Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
//        }
//        try {
//            fr.close();
//        } catch (IOException ex) {
//            Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
//        }

        LinkedBlockingDeque<Path> aconvertir = new LinkedBlockingDeque<Path>();

        //   new Thread(new Converter(aconvertir, convertidos, espacioAhorrado, tiempototal, cont, tiempoDormido)).start();
        try {
            new DirectoryObserver(aconvertir, convertidos).procesarEventos();
        } catch (IOException ex) {
            Logger.getLogger(Convertidor.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}
