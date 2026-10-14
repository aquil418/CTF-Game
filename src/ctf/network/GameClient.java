package ctf.network;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Client-side input, camera, HUD, sprite animation, and rendering. */
public class GameClient extends JPanel implements KeyListener {
    public static final int WORLD_WIDTH = 1600;
    public static final int WORLD_HEIGHT = 900;
    private static final int PLAYER_SIZE = 32; // fixed sprite box; keep consistent across animation frames
    private static final long RUN_FRAME_MS = 100L;
    private static final long JUMP_FRAME_MS = 100L;

    private final String host;
    private final int port;
    private final String playerName;
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;

    private volatile int myId = -1;
    private volatile String myTeam = "CONNECTING";
    private volatile String phase = "LOBBY";
    private volatile int hostId = -1;
    private volatile int redScore, blueScore;
    private volatile int redFlagX, redFlagY, redCarrier = -1;
    private volatile int blueFlagX, blueFlagY, blueCarrier = -1;
    private volatile double myEnergy = 100;
    private volatile boolean myFlying;
    private volatile int myHealth = 100;
    private volatile String myWeapon = "PISTOL";
    private volatile int myAmmo = 12, myReserve = 60;
    private volatile boolean myReloading;
    private volatile double aimAngle = 0;

    private final List<Player> players = new ArrayList<>();
    private final List<Projectile> projectiles = new ArrayList<>();
    private final Map<Integer, AttackFx> attackEffects = new HashMap<>();
    private final Object playersLock = new Object();
    private final List<String> chatMessages = new ArrayList<>();
    private final Object chatLock = new Object();
    private Image backgroundImage;
    private BufferedImage pistolImage, shotgunImage, bladeImage;
    private final List<List<BufferedImage>> runFrames = new ArrayList<>();
    private final List<List<BufferedImage>> jumpFrames = new ArrayList<>();
    private final Map<Integer, SpriteState> spriteStates = new HashMap<>();
    private volatile boolean left, right, jumpHeld, boostHeld;
    private double cameraX, cameraY;
    private volatile double currentZoom = 1.0;
    private static final double CAMERA_ZOOM = 1.55; // slightly closer view of the map
    private long lastPaintNanos;
    private boolean windowedMode;

    public static class Player {
        public int id;
        public String name, team;
        public int x, y; // world coordinates; y is feet
        public int vx, vy;
        public boolean grounded, flying, ready, host;
        public int energy = 100;
        public String carrying;
        public int health = 100, ammo = 12, reserve = 60;
        public String weapon = "PISTOL";
        public double aimAngle;
        public boolean reloading;
    }

    public static class Projectile {
        public int id, ownerId, x, y, vx, vy;
        public String weapon;
    }

    private static final class AttackFx {
        final String type; final double angle; final long at;
        AttackFx(String type,double angle){this.type=type;this.angle=angle;this.at=System.currentTimeMillis();}
    }

    private static final class SpriteState {
        int lastX, lastY;
        boolean initialized, facingLeft;
        long animationStart = System.currentTimeMillis();
    }

    public GameClient(String host, int port, String playerName) {
        this.host = host;
        this.port = port;
        this.playerName = playerName;
        URL bg = getClass().getResource("/ctf/ui/ctf_map.jpeg");
        if (bg != null) backgroundImage = new ImageIcon(bg).getImage();
        pistolImage = loadWeaponImage("/ctf/ui/weapons/pistol.png");
        shotgunImage = loadWeaponImage("/ctf/ui/weapons/shotgun.png");
        bladeImage = loadWeaponImage("/ctf/ui/weapons/energy_blade.png");
        for (int i = 1; i <= 6; i++) {
            List<BufferedImage> run = loadGifFrames("/ctf/ui/sprites/character" + i + ".gif");
            List<BufferedImage> jump = loadGifFrames("/ctf/ui/sprites/jump" + i + ".gif");
            normalizeAnimationPair(run, jump); // same frame canvas prevents apparent size changes
            runFrames.add(run);
            jumpFrames.add(jump);
        }
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        addKeyListener(this);
        setBackground(Color.BLACK);
        setPreferredSize(new Dimension(1100, 700));
        installKeyBindings();
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) { updateAimFromMouse(e); }
            @Override public void mouseDragged(MouseEvent e) { updateAimFromMouse(e); }
        });
    }

    public void connect() throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out = new PrintWriter(socket.getOutputStream(), true);
        out.println("JOIN " + playerName);
        Thread listener = new Thread(this::listen, "ctf-client-listener");
        listener.setDaemon(true);
        listener.start();
    }

    private void listen() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("WELCOME ")) {
                    String[] t = line.split(" ");
                    myId = Integer.parseInt(t[1]); myTeam = t[2];
                } else if (line.startsWith("PHASE ")) {
                    phase = line.substring(6).trim();
                } else if (line.startsWith("HOST ")) {
                    hostId = Integer.parseInt(line.substring(5).trim());
                } else if (line.startsWith("STATE ")) {
                    String[] t = line.split(" ");
                    redScore = Integer.parseInt(t[1]); blueScore = Integer.parseInt(t[2]);
                    synchronized (playersLock) { players.clear(); }
                } else if (line.startsWith("FLAG ")) {
                    String[] t = line.split(" ");
                    if ("RED".equals(t[1])) { redFlagX=Integer.parseInt(t[2]); redFlagY=Integer.parseInt(t[3]); redCarrier=Integer.parseInt(t[4]); }
                    else { blueFlagX=Integer.parseInt(t[2]); blueFlagY=Integer.parseInt(t[3]); blueCarrier=Integer.parseInt(t[4]); }
                } else if ("PROJECTILES".equals(line)) {
                    synchronized (playersLock) { projectiles.clear(); }
                } else if (line.startsWith("PROJECTILE ")) {
                    String[] t=line.split(" ");
                    if (t.length>=8) {
                        Projectile shot=new Projectile();
                        shot.id=Integer.parseInt(t[1]); shot.ownerId=Integer.parseInt(t[2]); shot.weapon=t[3];
                        shot.x=Integer.parseInt(t[4]); shot.y=Integer.parseInt(t[5]);
                        shot.vx=Integer.parseInt(t[6]); shot.vy=Integer.parseInt(t[7]);
                        synchronized(playersLock) { projectiles.add(shot); }
                    }
                } else if (line.startsWith("FX ")) {
                    String[] t=line.split(" ");
                    if(t.length>=4) synchronized(playersLock) { attackEffects.put(Integer.parseInt(t[1]),new AttackFx(t[2],Double.parseDouble(t[3]))); }
                } else if (line.startsWith("PLAYER ")) {
                    String[] t = line.split(" ");
                    if (t.length < 9) continue;
                    Player p = new Player();
                    p.id=Integer.parseInt(t[1]); p.name=t[2]; p.team=t[3];
                    p.ready="READY".equals(t[4]); p.host="HOST".equals(t[5]);
                    p.x=Integer.parseInt(t[6]); p.y=Integer.parseInt(t[7]);
                    p.carrying="-".equals(t[8]) ? null : t[8];
                    if (t.length >= 14) {
                        p.vx=Integer.parseInt(t[9]); p.vy=Integer.parseInt(t[10]);
                        p.grounded="1".equals(t[11]); p.flying="1".equals(t[12]);
                        p.energy=Integer.parseInt(t[13]);
                    }
                    if (t.length >= 20) {
                        p.health=Integer.parseInt(t[14]); p.weapon=t[15];
                        p.ammo=Integer.parseInt(t[16]); p.reserve=Integer.parseInt(t[17]);
                        p.aimAngle=Double.parseDouble(t[18]); p.reloading="1".equals(t[19]);
                    }
                    if (p.id == myId) {
                        myTeam=p.team; myEnergy=p.energy; myFlying=p.flying;
                        myHealth=p.health; myWeapon=p.weapon; myAmmo=p.ammo; myReserve=p.reserve; myReloading=p.reloading;
                    }
                    synchronized (playersLock) {
                        SpriteState ss=spriteStates.computeIfAbsent(p.id, id -> new SpriteState());
                        if (!ss.initialized) { ss.lastX=p.x; ss.lastY=p.y; ss.initialized=true; }
                        else {
                            if (p.x < ss.lastX) ss.facingLeft=true;
                            else if (p.x > ss.lastX) ss.facingLeft=false;
                            ss.lastX=p.x; ss.lastY=p.y;
                        }
                        players.add(p);
                    }
                } else if (line.startsWith("CHAT ")) {
                    synchronized (chatLock) {
                        chatMessages.add(line.substring(5));
                        if (chatMessages.size()>100) chatMessages.remove(0);
                    }
                } else if (line.startsWith("ERROR ")) {
                    String error=line.substring(6);
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,error.replace('_',' '),"Connection Error",JOptionPane.ERROR_MESSAGE));
                } else if ("END".equals(line)) {
                    SwingUtilities.invokeLater(this::repaint);
                }
            }
            // readLine() returning null means the remote endpoint closed the TCP connection.
            if (!"DISCONNECTED".equals(phase)) phase = "DISCONNECTED";
            SwingUtilities.invokeLater(this::repaint);
        } catch (IOException e) {
            phase="DISCONNECTED";
            SwingUtilities.invokeLater(this::repaint);
        }
    }

    public List<Player> getPlayersSnapshot() { synchronized(playersLock) { return new ArrayList<>(players); } }
    public List<String> getChatSnapshot() { synchronized(chatLock) { return new ArrayList<>(chatMessages); } }
    public String getPhase() { return phase; }
    public int getMyId() { return myId; }
    public int getHostId() { return hostId; }
    public String getMyTeam() { return myTeam; }
    public String getPlayerName() { return playerName; }
    public boolean isHost() { return myId==hostId; }
    public void chooseTeam(String team) { if(out!=null) out.println("TEAM "+team); }
    public void setReady(boolean ready) { if(out!=null) out.println(ready?"READY":"UNREADY"); }
    public void startMatch() { if(out!=null) out.println("START"); }
    public void sendChat(String message) { if(out!=null && message!=null && !message.trim().isEmpty()) out.println("CHAT "+message.trim()); }
    public void closeConnection() { try { if(socket!=null) socket.close(); } catch(IOException ignored) {} }
    private void sendMelee() { if(out!=null) out.println("MELEE"); }
    private void shootWeapon() { if(out!=null) out.println("SHOOT"); }
    private void reloadWeapon() { if(out!=null) out.println("RELOAD"); }
    private void selectWeapon(String weapon) { if(out!=null) out.println("WEAPON "+weapon); }

    private void updateAimFromMouse(MouseEvent e) {
        List<Player> snapshot=getPlayersSnapshot();
        Player me=null; for(Player p:snapshot) if(p.id==myId){me=p;break;}
        if(me==null || currentZoom<=0) return;
        double worldX=cameraX+e.getX()/currentZoom;
        double worldY=cameraY+e.getY()/currentZoom;
        aimAngle=Math.atan2(worldY-(me.y-21),worldX-me.x);
        if(out!=null && "PLAYING".equals(phase)) out.println("AIM "+aimAngle);
    }

    private void sendInput() {
        if(out!=null) out.println("INPUT "+(left?1:0)+" "+(right?1:0)+" "+(jumpHeld?1:0)+" "+(boostHeld?1:0));
    }

    private void installKeyBindings() {
        // Key bindings keep movement responsive even when a child Swing component takes focus.
        bindKey("pressed A", "leftOn", () -> { left=true; sendInput(); });
        bindKey("released A", "leftOff", () -> { left=false; sendInput(); });
        bindKey("pressed LEFT", "leftArrowOn", () -> { left=true; sendInput(); });
        bindKey("released LEFT", "leftArrowOff", () -> { left=false; sendInput(); });
        bindKey("pressed D", "rightOn", () -> { right=true; sendInput(); });
        bindKey("released D", "rightOff", () -> { right=false; sendInput(); });
        bindKey("pressed RIGHT", "rightArrowOn", () -> { right=true; sendInput(); });
        bindKey("released RIGHT", "rightArrowOff", () -> { right=false; sendInput(); });
        bindKey("pressed SPACE", "jumpOn", () -> { jumpHeld=true; sendInput(); });
        bindKey("released SPACE", "jumpOff", () -> { jumpHeld=false; sendInput(); });
        // SPACE jumps. W / Up Arrow activate the flight booster.
        bindKey("pressed W", "boostWOn", () -> { boostHeld=true; sendInput(); });
        bindKey("released W", "boostWOff", () -> { boostHeld=false; sendInput(); });
        bindKey("pressed UP", "boostUpOn", () -> { boostHeld=true; sendInput(); });
        bindKey("released UP", "boostUpOff", () -> { boostHeld=false; sendInput(); });
        bindKey("pressed Z", "melee", this::sendMelee);
        bindKey("pressed X", "shoot", this::shootWeapon);
        bindKey("pressed R", "reload", this::reloadWeapon);
        bindKey("pressed 1", "pistol", () -> selectWeapon("PISTOL"));
        bindKey("pressed 2", "shotgun", () -> selectWeapon("SHOTGUN"));
        bindKey("pressed 3", "blade", () -> selectWeapon("BLADE"));
        bindKey("pressed ESCAPE", "escape", () -> {
            Window w=SwingUtilities.getWindowAncestor(this);
            if(w instanceof GameFrame frame) frame.toggleWindowedMode();
        });
        bindKey("pressed F11", "fullscreen", () -> {
            Window w=SwingUtilities.getWindowAncestor(this);
            if(w instanceof GameFrame frame) frame.toggleWindowedMode();
        });
    }
    private void bindKey(String stroke,String name,Runnable action) {
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(stroke),name);
        getActionMap().put(name,new AbstractAction(){@Override public void actionPerformed(ActionEvent e){action.run();}});
    }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D screen=(Graphics2D)g.create();
        screen.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        screen.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        List<Player> snapshot=getPlayersSnapshot();
        Player me=null; for(Player p:snapshot) if(p.id==myId){me=p;break;}

        double zoom=Math.max(getWidth()/(double)WORLD_WIDTH,getHeight()/(double)WORLD_HEIGHT)*CAMERA_ZOOM;
        zoom=Math.max(0.65,zoom);
        currentZoom=zoom;
        double viewW=getWidth()/zoom, viewH=getHeight()/zoom;
        double targetX=me==null? (WORLD_WIDTH-viewW)/2.0 : me.x-viewW/2.0;
        double targetY=me==null? (WORLD_HEIGHT-viewH)/2.0 : me.y-viewH*0.56;
        targetX=clamp(targetX,0,Math.max(0,WORLD_WIDTH-viewW));
        targetY=clamp(targetY,0,Math.max(0,WORLD_HEIGHT-viewH));
        long now=System.nanoTime();
        double smoothing=lastPaintNanos==0?1.0:Math.min(1.0,(now-lastPaintNanos)/80_000_000.0);
        lastPaintNanos=now;
        cameraX += (targetX-cameraX)*smoothing;
        cameraY += (targetY-cameraY)*smoothing;
        cameraX=clamp(cameraX,0,Math.max(0,WORLD_WIDTH-viewW));
        cameraY=clamp(cameraY,0,Math.max(0,WORLD_HEIGHT-viewH));

        Graphics2D world=(Graphics2D)screen.create();
        world.translate(-cameraX*zoom,-cameraY*zoom);
        world.scale(zoom,zoom);
        if(backgroundImage!=null) world.drawImage(backgroundImage,0,0,WORLD_WIDTH,WORLD_HEIGHT,this);
        else { world.setColor(new Color(30,90,130)); world.fillRect(0,0,WORLD_WIDTH,WORLD_HEIGHT); }
        world.setColor(new Color(0,0,0,28)); world.fillRect(0,0,WORLD_WIDTH,WORLD_HEIGHT);
        // Team base zones, drawn in world coordinates.
        world.setColor(new Color(255,55,55,72)); world.fillOval((int)RED_BASE_X()-60,370,120,100);
        world.setColor(new Color(65,95,255,72)); world.fillOval((int)BLUE_BASE_X()-60,370,120,100);
        drawFlag(world,redFlagX,redFlagY,Color.RED);
        drawFlag(world,blueFlagX,blueFlagY,Color.BLUE);
        List<Projectile> shots;
        synchronized(playersLock) { shots=new ArrayList<>(projectiles); }
        for (Projectile shot : shots) {
            Color shotColor="SHOTGUN".equals(shot.weapon)?new Color(255,155,50):new Color(255,240,120);
            world.setColor(new Color(0,0,0,120)); world.fillOval(shot.x-5,shot.y-5,10,10);
            world.setColor(shotColor); world.fillOval(shot.x-3,shot.y-3,6,6);
            world.setColor(new Color(shotColor.getRed(),shotColor.getGreen(),shotColor.getBlue(),120));
            world.drawLine(shot.x,shot.y,shot.x-(int)(shot.vx*0.025),shot.y-(int)(shot.vy*0.025));
        }

        for(Player p:snapshot) {
            Color teamColor="RED".equals(p.team)?new Color(240,60,60):new Color(65,100,255);
            world.setColor(new Color(teamColor.getRed(),teamColor.getGreen(),teamColor.getBlue(),105));
            world.fillOval((int)p.x-17,(int)p.y-4,34,12);
            int characterIndex=Math.floorMod(p.id-1,6);
            SpriteState state;
            synchronized(playersLock){state=spriteStates.get(p.id);}
            List<BufferedImage> frames=(p.flying||!p.grounded)?jumpFrames.get(characterIndex):runFrames.get(characterIndex);
            boolean moving=Math.abs(p.vx)>1;
            BufferedImage sprite=chooseFrame(frames,state,moving,now,p.flying||!p.grounded);
            int drawX=(int)p.x-PLAYER_SIZE/2, drawY=(int)p.y-PLAYER_SIZE;
            if(sprite!=null) {
                if(state!=null&&state.facingLeft) world.drawImage(sprite,drawX+PLAYER_SIZE,drawY,-PLAYER_SIZE,PLAYER_SIZE,this);
                else world.drawImage(sprite,drawX,drawY,PLAYER_SIZE,PLAYER_SIZE,this);
            } else { world.setColor(teamColor); world.fillOval((int)p.x-12,(int)p.y-PLAYER_SIZE,24,24); }
            // Render the selected weapon in the character's hands, rotated toward the aim angle.
            drawHeldWeapon(world, p);
            if(p.id==myId){world.setColor(Color.YELLOW);world.setStroke(new BasicStroke(2f));world.drawOval((int)p.x-22,(int)p.y-PLAYER_SIZE-3,44,44);}
            world.setFont(new Font("Arial",Font.BOLD,12)); FontMetrics fm=world.getFontMetrics();
            int nameX=(int)p.x-fm.stringWidth(p.name)/2;
            world.setColor(new Color(0,0,0,170));world.drawString(p.name,nameX+1,drawY-4+1);
            world.setColor(Color.WHITE);world.drawString(p.name,nameX,drawY-4);
            if(p.carrying!=null){Color fc="RED".equals(p.carrying)?Color.RED:Color.BLUE;world.setColor(Color.DARK_GRAY);world.fillRect((int)p.x+9,(int)p.y-34,3,22);world.setColor(fc);world.fillRect((int)p.x+12,(int)p.y-34,14,9);}
            if(p.flying){world.setColor(new Color(100,240,255,190));world.drawOval((int)p.x-22,(int)p.y-PLAYER_SIZE-5,44,44);}
            // Server-authoritative health bar above each player.
            int hpW=38, hpH=5, hpX=(int)p.x-hpW/2, hpY=drawY-21;
            world.setColor(new Color(0,0,0,190)); world.fillRect(hpX-1,hpY-1,hpW+2,hpH+2);
            world.setColor(p.health>50?new Color(70,220,100):(p.health>25?new Color(255,185,55):new Color(255,65,65)));
            world.fillRect(hpX,hpY,(int)(hpW*Math.max(0,Math.min(1,p.health/100.0))),hpH);
        }
        Map<Integer,AttackFx> fxSnapshot;
        synchronized(playersLock) { fxSnapshot=new HashMap<>(attackEffects); }
        long fxNow=System.currentTimeMillis();
        for (Player p:snapshot) {
            AttackFx fx=fxSnapshot.get(p.id);
            if (fx==null || fxNow-fx.at>220) continue;
            double centerY=p.y-PLAYER_SIZE*0.55;
            if ("FIST".equals(fx.type) || "BLADE".equals(fx.type)) {
                double reach="BLADE".equals(fx.type)?78:43;
                Color c="BLADE".equals(fx.type)?new Color(80,235,255,230):new Color(255,205,140,220);
                world.setColor(c); world.setStroke(new BasicStroke("BLADE".equals(fx.type)?5f:4f));
                for (int j=-1;j<=1;j++) {
                    double a=fx.angle+j*0.18;
                    world.drawLine((int)(p.x+Math.cos(a)*18),(int)(centerY+Math.sin(a)*18),
                                   (int)(p.x+Math.cos(a)*reach),(int)(centerY+Math.sin(a)*reach));
                }
            } else {
                double mx=p.x+Math.cos(fx.angle)*25, my=centerY+Math.sin(fx.angle)*25;
                world.setColor("SHOTGUN".equals(fx.type)?new Color(255,160,55,230):new Color(255,245,160,230));
                world.fillOval((int)mx-7,(int)my-7,14,14);
            }
        }
        synchronized(playersLock) { attackEffects.entrySet().removeIf(e -> fxNow-e.getValue().at>300); }
        world.dispose();
        drawEnemyIndicators(screen, snapshot, me, zoom);
        drawHud(screen,me);
        screen.dispose();
    }

    private BufferedImage loadWeaponImage(String resource) {
        try {
            URL url = getClass().getResource(resource);
            if (url == null) {
                System.err.println("Missing weapon sprite: " + resource);
                return null;
            }
            return ImageIO.read(url);
        } catch (IOException ex) {
            System.err.println("Unable to load weapon sprite " + resource + ": " + ex.getMessage());
            return null;
        }
    }

    private void drawHeldWeapon(Graphics2D world, Player p) {
        BufferedImage image;
        int drawW, drawH;
        double angleOffset = 0.0;
        switch (p.weapon == null ? "PISTOL" : p.weapon) {
            case "SHOTGUN": image = shotgunImage; drawW = 43; drawH = 12; break;
            case "BLADE": image = bladeImage; drawW = 34; drawH = 34; angleOffset = Math.PI / 4.0; break;
            case "PISTOL": image = pistolImage; drawW = 25; drawH = 17; break;
            default: return;
        }
        if (image == null) return;
        boolean isBlade = "BLADE".equals(p.weapon);
        // Gun sprites are authored pointing right. When aiming left, mirror the
        // sprite and rotate through the equivalent upper half-plane so the gun
        // does not turn upside down when the cursor crosses behind the player.
        boolean flipGun = !isBlade && (Math.cos(p.aimAngle) < 0.0);
        double drawAngle = p.aimAngle;
        if (flipGun) {
            if (drawAngle > Math.PI / 2.0) drawAngle -= Math.PI;
            else if (drawAngle < -Math.PI / 2.0) drawAngle += Math.PI;
        }
        double angle = drawAngle + angleOffset;
        double handX = p.x + Math.cos(p.aimAngle) * 7.0;
        double handY = p.y - PLAYER_SIZE * 0.62 + Math.sin(p.aimAngle) * 5.0;
        Graphics2D held = (Graphics2D) world.create();
        held.translate(handX, handY);
        held.rotate(angle);
        if (isBlade) {
            held.drawImage(image, -drawW / 3, -drawH / 2, drawW, drawH, this);
        } else if (flipGun) {
            // Negative width mirrors the gun while keeping the barrel aimed left.
            held.drawImage(image, 4 + drawW, -drawH / 2, -drawW, drawH, this);
        } else {
            held.drawImage(image, -4, -drawH / 2, drawW, drawH, this);
        }
        held.dispose();
    }

    private static double RED_BASE_X(){return 100;}
    private static double BLUE_BASE_X(){return 1500;}
    /** Normalizes run and jump frames to one shared transparent canvas per character. */
    private static void normalizeAnimationPair(List<BufferedImage> run, List<BufferedImage> jump) {
        int maxW=1, maxH=1;
        for (BufferedImage image : run) { maxW=Math.max(maxW,image.getWidth()); maxH=Math.max(maxH,image.getHeight()); }
        for (BufferedImage image : jump) { maxW=Math.max(maxW,image.getWidth()); maxH=Math.max(maxH,image.getHeight()); }
        normalizeFrames(run,maxW,maxH);
        normalizeFrames(jump,maxW,maxH);
    }
    private static void normalizeFrames(List<BufferedImage> frames,int width,int height) {
        for (int i=0;i<frames.size();i++) {
            BufferedImage src=frames.get(i);
            BufferedImage canvas=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
            Graphics2D g=canvas.createGraphics();
            g.drawImage(src,(width-src.getWidth())/2,(height-src.getHeight())/2,null);
            g.dispose(); frames.set(i,canvas);
        }
    }

    /** Draws an edge arrow for every enemy outside the local camera view. */
    private void drawEnemyIndicators(Graphics2D g,List<Player> snapshot,Player me,double zoom) {
        if(me==null)return;
        int margin=26;
        for(Player enemy:snapshot) {
            if(enemy.id==myId || enemy.team.equals(myTeam))continue;
            double sx=(enemy.x-cameraX)*zoom;
            double sy=(enemy.y-cameraY)*zoom;
            if(sx>=margin && sx<=getWidth()-margin && sy>=margin && sy<=getHeight()-margin)continue;
            double dx=sx-getWidth()/2.0, dy=sy-getHeight()/2.0;
            if(dx==0&&dy==0)continue;
            double scale=Math.min((getWidth()/2.0-margin)/Math.max(1,Math.abs(dx)),
                                  (getHeight()/2.0-margin)/Math.max(1,Math.abs(dy)));
            double cx=getWidth()/2.0+dx*scale, cy=getHeight()/2.0+dy*scale;
            double angle=Math.atan2(dy,dx);
            int tipX=(int)(cx+Math.cos(angle)*13), tipY=(int)(cy+Math.sin(angle)*13);
            int backX=(int)(cx-Math.cos(angle)*9), backY=(int)(cy-Math.sin(angle)*9);
            int perpX=(int)(Math.cos(angle+Math.PI/2)*8), perpY=(int)(Math.sin(angle+Math.PI/2)*8);
            Polygon arrow=new Polygon(new int[]{tipX,backX+perpX,backX-perpX},new int[]{tipY,backY+perpY,backY-perpY},3);
            g.setColor(new Color(255,65,65,235));g.fillPolygon(arrow);
            g.setColor(Color.WHITE);g.setStroke(new BasicStroke(1.5f));g.drawPolygon(arrow);
            g.setFont(new Font("Arial",Font.BOLD,11));
            String label=enemy.name;
            int labelW=g.getFontMetrics().stringWidth(label);
            g.drawString(label,(int)cx-labelW/2,(int)cy-14);
        }
    }

    private BufferedImage chooseFrame(List<BufferedImage> frames,SpriteState state,boolean moving,long now,boolean airborne){
        if(frames==null||frames.isEmpty())return null;
        if(!moving&&!airborne)return frames.get(0);
        long elapsed=(System.currentTimeMillis()-(state==null?0:state.animationStart));
        long frameMs=airborne?JUMP_FRAME_MS:RUN_FRAME_MS;
        return frames.get((int)((Math.max(0,elapsed)/frameMs)%frames.size()));
    }

    private void drawHud(Graphics2D g,Player me){
        int margin=18;
        g.setColor(new Color(0,0,0,170));g.fillRoundRect(margin,margin,280,100,16,16);
        g.fillRoundRect(getWidth()-298,margin,280,100,16,16);
        g.setFont(new Font("Arial",Font.BOLD,24));g.setColor(new Color(255,105,105));g.drawString("RED  "+redScore,margin+16,margin+31);
        g.setColor(new Color(115,145,255));g.drawString("BLUE  "+blueScore,getWidth()-280,margin+31);
        g.setFont(new Font("Arial",Font.BOLD,14));g.setColor(Color.WHITE);
        g.drawString("You: "+playerName+"  |  Team: "+myTeam,margin+16,margin+56);
        g.drawString("A/D: Move | SPACE: Jump",getWidth()-280,margin+55);
        g.drawString("W/UP: Fly | Z: Punch | X: Use",getWidth()-280,margin+77);
        int barW=Math.min(390,Math.max(240,getWidth()/3));int barH=18;int bx=(getWidth()-barW)/2;int by=24;
        g.setColor(new Color(0,0,0,175));g.fillRoundRect(bx-12,by-8,barW+24,52,14,14);
        g.setFont(new Font("Arial",Font.BOLD,13));g.setColor(Color.WHITE);g.drawString("FLIGHT ENERGY",bx,by+4);
        g.setColor(new Color(35,45,60));g.fillRoundRect(bx,by+12,barW,barH,9,9);
        int fill=(int)(barW*Math.max(0,Math.min(1,myEnergy/100.0)));
        if(fill>0){g.setColor(myEnergy<25?new Color(255,90,65):new Color(40,205,235));g.fillRoundRect(bx,by+12,fill,barH,9,9);}
        g.setColor(Color.WHITE);g.drawRoundRect(bx,by+12,barW,barH,9,9);
        String state=myFlying?"BOOST ACTIVE":"RECHARGES ON GROUND";
        g.setFont(new Font("Arial",Font.PLAIN,11));g.drawString(state,bx,by+45);
        // Combat panel remains screen-fixed while the camera scrolls.
        int panelW=300, panelH=58, panelX=(getWidth()-panelW)/2, panelY=getHeight()-panelH-18;
        g.setColor(new Color(0,0,0,185));g.fillRoundRect(panelX,panelY,panelW,panelH,14,14);
        g.setFont(new Font("Arial",Font.BOLD,15));g.setColor(Color.WHITE);
        String weaponLabel="BLADE".equals(myWeapon)?"ENERGY BLADE":myWeapon;
        g.drawString("HP: "+myHealth+" / 100",panelX+12,panelY+21);
        g.drawString("Weapon: "+weaponLabel,panelX+12,panelY+42);
        String ammoLabel="BLADE".equals(myWeapon)?"Unlimited":(myReloading?"RELOADING...":myAmmo+" / "+myReserve);
        g.drawString("Ammo: "+ammoLabel,panelX+165,panelY+30);
        g.setFont(new Font("Arial",Font.PLAIN,11));g.drawString("1 Pistol  2 Shotgun  3 Blade  R Reload",panelX+12,panelY-5);
        if("DISCONNECTED".equals(phase)){g.setColor(new Color(150,0,0,210));g.fillRoundRect(getWidth()/2-170,getHeight()/2-30,340,60,14,14);g.setColor(Color.WHITE);g.setFont(new Font("Arial",Font.BOLD,20));g.drawString("DISCONNECTED",getWidth()/2-90,getHeight()/2+7);}
    }

    private void drawFlag(Graphics2D g,int x,int y,Color color){
        if(x<0)return;
        g.setColor(new Color(0,0,0,100));g.fillOval(x-12,y-3,24,8);
        g.setColor(new Color(70,55,45));g.fillRect(x-2,y-32,4,32);
        g.setColor(color);Polygon flag=new Polygon(new int[]{x+2,x+23,x+2},new int[]{y-31,y-25,y-16},3);g.fillPolygon(flag);
    }
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}

    private List<BufferedImage> loadGifFrames(String path){
        List<BufferedImage> frames=new ArrayList<>();URL url=getClass().getResource(path);if(url==null){System.err.println("Missing sprite: "+path);return frames;}
        try(InputStream stream=url.openStream();ImageInputStream imageStream=ImageIO.createImageInputStream(stream)){
            if(imageStream==null)return frames;Iterator<ImageReader> readers=ImageIO.getImageReaders(imageStream);if(!readers.hasNext())return frames;
            ImageReader reader=readers.next();try{reader.setInput(imageStream,false,false);int count=reader.getNumImages(true);for(int i=0;i<count;i++){BufferedImage frame=reader.read(i);if(frame!=null)frames.add(frame);}}finally{reader.dispose();}
        }catch(IOException e){System.err.println("Unable to read "+path+": "+e.getMessage());}
        // Trim the transparent margin to a shared content box so every frame remains aligned.
        if(frames.isEmpty())return frames;
        int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,maxX=-1,maxY=-1;
        for(BufferedImage f:frames)for(int y=0;y<f.getHeight();y++)for(int x=0;x<f.getWidth();x++)if(((f.getRGB(x,y)>>>24)&255)>8){minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);}
        if(maxX<minX||maxY<minY)return new ArrayList<>();
        List<BufferedImage> result=new ArrayList<>();
        for(BufferedImage f:frames){int x=Math.min(minX,f.getWidth()-1),y=Math.min(minY,f.getHeight()-1);int w=Math.min(maxX-minX+1,f.getWidth()-x),h=Math.min(maxY-minY+1,f.getHeight()-y);BufferedImage crop=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);Graphics2D cg=crop.createGraphics();cg.drawImage(f,0,0,w,h,x,y,x+w,y+h,null);cg.dispose();result.add(crop);}
        return result;
    }

    @Override public void keyTyped(KeyEvent e){}
    @Override public void keyPressed(KeyEvent e){handleKey(e.getKeyCode(),true);}
    @Override public void keyReleased(KeyEvent e){handleKey(e.getKeyCode(),false);}
    private void handleKey(int code,boolean pressed){
        switch(code){case KeyEvent.VK_A:case KeyEvent.VK_LEFT:left=pressed;break;case KeyEvent.VK_D:case KeyEvent.VK_RIGHT:right=pressed;break;case KeyEvent.VK_SPACE:jumpHeld=pressed;break;case KeyEvent.VK_W:case KeyEvent.VK_UP:boostHeld=pressed;break;default:return;}sendInput();
    }

    public static class GameFrame extends JFrame {
        private final GameClient client;
        private final GameServer serverToStop;
        private boolean windowed;
        private boolean changingDisplayMode;
        private boolean disconnectHandled;
        private Rectangle previousBounds;
        private Timer disconnectWatcher;
        public GameFrame(GameClient client) { this(client, null); }
        public GameFrame(GameClient client, GameServer serverToStop){
            super("Capture The Flag - "+client.playerName);this.client=client;this.serverToStop=serverToStop;
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);setContentPane(client);setUndecorated(true);setResizable(false);setMinimumSize(new Dimension(640,420));
            GraphicsConfiguration gc=GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
            setBounds(gc.getBounds());setLocation(gc.getBounds().x,gc.getBounds().y);
            addWindowListener(new WindowAdapter(){@Override public void windowClosed(WindowEvent e){
                if(disconnectWatcher!=null) disconnectWatcher.stop();
                if(!changingDisplayMode){client.closeConnection();if(serverToStop!=null)serverToStop.stop();}
            }});
            disconnectWatcher = new Timer(300, e -> {
                if (!disconnectHandled && "DISCONNECTED".equals(client.getPhase())) {
                    disconnectHandled = true;
                    disconnectWatcher.stop();
                    int choice = JOptionPane.showOptionDialog(this,
                            "The connection to the game server was lost.\nReturn to the main menu?",
                            "Disconnected", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                            null, new Object[]{"RETURN TO MAIN MENU"}, "RETURN TO MAIN MENU");
                    client.closeConnection();
                    if (serverToStop != null) serverToStop.stop();
                    dispose();
                    SwingUtilities.invokeLater(() -> new ctf.ui.GameWindow().setVisible(true));
                }
            });
            disconnectWatcher.start();
        }
        public void toggleWindowedMode(){
            // Recreate only the native frame peer; preserve the live GameClient/socket.
            changingDisplayMode=true;
            if(!windowed){
                previousBounds=getBounds();
                dispose();
                setUndecorated(false);
                setResizable(true);
                int w=Math.min(1280,Math.max(800,getToolkit().getScreenSize().width-120));
                int h=Math.min(800,Math.max(520,getToolkit().getScreenSize().height-120));
                GraphicsConfiguration gc=GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
                Rectangle screen=gc.getBounds();
                setBounds(screen.x+(screen.width-w)/2,screen.y+(screen.height-h)/2,w,h);
                windowed=true;
            } else {
                dispose();
                setUndecorated(true);
                setResizable(false);
                GraphicsConfiguration gc=GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
                setBounds(gc.getBounds());
                windowed=false;
            }
            setVisible(true);
            changingDisplayMode=false;
            client.requestFocusInWindow();client.repaint();
        }
        @Override public void setVisible(boolean visible){super.setVisible(visible);if(visible)SwingUtilities.invokeLater(()->{client.requestFocusInWindow();client.repaint();});}
    }
}
