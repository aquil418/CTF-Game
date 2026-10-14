package ctf.network;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Authoritative TCP server for the CTF platform game. Coordinates are world pixels; y is the player's feet. */
public class GameServer {
    public static final int PORT = 5000;
    public static final int WORLD_WIDTH = 1600, WORLD_HEIGHT = 900;
    private static final int TICK_MS = 33;
    private static final double DT = TICK_MS / 1000.0;

    // Tunable platformer physics (pixels / seconds and seconds-based energy rates).
    private static final double MOVE_SPEED = 300.0;
    private static final double GRAVITY = 1450.0;
    private static final double JUMP_VELOCITY = -590.0;
    private static final double MAX_FALL_SPEED = 850.0;
    private static final double BOOST_UP_SPEED = 520.0; // stronger upward thrust
    private static final double MAX_ENERGY = 100.0;
    private static final double ENERGY_DRAIN_PER_SEC = 48.0; // shorter flight duration
    private static final double ENERGY_REGEN_PER_SEC = 24.0; // only while grounded
    private static final double PLAYER_HALF_WIDTH = 15.0;
    private static final double PLAYER_HEIGHT = 38.0;

    // Combat balance: all attacks are validated and simulated on the server.
    private static final int MAX_HEALTH = 100;
    private static final long RESPAWN_DELAY_MS = 2000;
    private static final long SPAWN_PROTECTION_MS = 1200;
    private static final long FIST_COOLDOWN_MS = 500;
    private static final long BLADE_COOLDOWN_MS = 650;
    private static final long PISTOL_COOLDOWN_MS = 300;
    private static final long SHOTGUN_COOLDOWN_MS = 800;
    private static final long PISTOL_RELOAD_MS = 1200;
    private static final long SHOTGUN_RELOAD_MS = 1500;
    private static final double PISTOL_SPEED = 650.0;
    private static final double SHOTGUN_SPEED = 520.0;
    private static final double PROJECTILE_RADIUS = 5.0;
    private static final double MAX_PROJECTILE_LIFE = 1.25;

    private static final double RED_BASE_X = 100, BLUE_BASE_X = 1500, BASE_FEET_Y = 420;
    private static final Object lock = new Object();
    private static final Map<Integer, Player> players = new LinkedHashMap<>();
    private static final Map<Integer, ClientHandler> handlers = new ConcurrentHashMap<>();
    private static final List<Projectile> projectiles = new ArrayList<>();
    private static int nextProjectileId = 1;
    private static final List<Platform> platforms = List.of(
        new Platform(0, 420, 315),       // red castle ledge
        new Platform(35, 330, 170),     // red tower top (flag platform)
        new Platform(315, 420, 510),     // left bridge and ledge
        new Platform(0, 550, 95),        // far-left small ledge
        new Platform(310, 570, 475),     // lower-left ledge
        new Platform(460, 620, 595),     // lower-left inner ledge
        new Platform(535, 530, 615),     // floating left platform
        new Platform(700, 505, 900),     // central platform
        new Platform(730, 375, 800),     // high centre platform
        new Platform(980, 530, 1065),    // floating right platform
        new Platform(1010, 620, 1150),   // lower-right inner ledge
        new Platform(1125, 570, 1295),   // lower-right ledge
        new Platform(1090, 420, 1600),   // right bridge and castle ledge
        new Platform(1430, 330, 1565),  // blue tower top (flag platform)
        new Platform(0, 700, 1600)      // ground / bridge top
    );

    // Flags are positioned on the tower-top platforms rather than the lower ledges.
    private static final double TOWER_FLAG_Y = 330;
    private static final Flag redFlag = new Flag(100, TOWER_FLAG_Y);
    private static final Flag blueFlag = new Flag(1500, TOWER_FLAG_Y);
    private static int redScore = 0, blueScore = 0;
    private static int nextId = 1, redCount = 0, blueCount = 0, hostId = -1;
    private static boolean hostConnectionPending;
    private static GamePhase phase = GamePhase.LOBBY;

    private final int port;
    private ServerSocket serverSocket;
    private volatile boolean running;
    private volatile boolean firstConnectionAccepted;
    private Thread loopThread;

    enum GamePhase { LOBBY, PLAYING, GAME_OVER }

    private static final class Platform {
        final double x1, top, x2;
        Platform(double x1, double top, double x2) { this.x1 = x1; this.top = top; this.x2 = x2; }
    }

    static class Player {
        int id;
        String name, team;
        double x, y, vx, vy; // x is centre; y is feet
        boolean left, right, jumpRequested, flying, grounded, ready, host;
        boolean facingLeft;
        double energy = MAX_ENERGY;
        String carrying;
        int health = MAX_HEALTH;
        String weapon = "PISTOL";
        int pistolAmmo = 12, pistolReserve = 60;
        int shotgunAmmo = 5, shotgunReserve = 25;
        double aimAngle = 0;
        long fistReadyAt, bladeReadyAt, pistolReadyAt, shotgunReadyAt;
        long reloadCompleteAt, respawnAt, invulnerableUntil;
        double knockbackX;
    }

    private static final class Projectile {
        final int id, ownerId, damage;
        final String team, weapon;
        double x, y, vx, vy, life = MAX_PROJECTILE_LIFE;
        Projectile(int id, int ownerId, String team, String weapon, double x, double y,
                   double vx, double vy, int damage) {
            this.id=id; this.ownerId=ownerId; this.team=team; this.weapon=weapon;
            this.x=x; this.y=y; this.vx=vx; this.vy=vy; this.damage=damage;
        }
    }

    static class Flag {
        double x, y;
        final double homeX, homeY;
        int carrierId = -1;
        Flag(double x, double y) { this.x=x; this.y=y; this.homeX=x; this.homeY=y; }
    }

    public GameServer(int port) { this.port = port; }

    public synchronized void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket(port);
        running = true;
        firstConnectionAccepted = false;
        synchronized (lock) { hostConnectionPending = false; }
        System.out.println("CTF platform server listening on port " + port);
        loopThread = new Thread(this::gameLoop, "ctf-game-loop");
        loopThread.setDaemon(true);
        loopThread.start();
        Thread accept = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    socket.setTcpNoDelay(true);
                    boolean isFirstConnection = !firstConnectionAccepted;
                    firstConnectionAccepted = true;
                    if (isFirstConnection) synchronized (lock) { hostConnectionPending = true; }
                    Thread clientThread = new Thread(new ClientHandler(socket, isFirstConnection), "ctf-client");
                    clientThread.setDaemon(true);
                    clientThread.start();
                } catch (IOException e) {
                    if (running) System.err.println("Accept error: " + e.getMessage());
                }
            }
        }, "ctf-accept");
        accept.setDaemon(true);
        accept.start();
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        for (ClientHandler h : handlers.values()) h.close();
        synchronized (lock) {
            players.clear(); handlers.clear(); projectiles.clear(); redCount = blueCount = 0;
            redScore = blueScore = 0; hostId = -1; phase = GamePhase.LOBBY;
            resetFlag(redFlag); resetFlag(blueFlag);
        }
    }

    private void gameLoop() {
        while (running) {
            long start = System.nanoTime();
            synchronized (lock) {
                if (phase == GamePhase.PLAYING) update();
                broadcastState();
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000L;
            try { Thread.sleep(Math.max(1, TICK_MS - elapsed)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static void update() {
        long now = System.currentTimeMillis();
        for (Player p : players.values()) {
            if (p.health <= 0) {
                if (now >= p.respawnAt) respawn(p);
                else continue;
            }
            if (p.reloadCompleteAt > 0 && now >= p.reloadCompleteAt) finishReload(p);
            double oldY = p.y;
            double horizontalInput = (p.right ? MOVE_SPEED : 0) - (p.left ? MOVE_SPEED : 0);
            p.vx = horizontalInput + p.knockbackX;
            p.knockbackX *= 0.82;
            if (Math.abs(p.knockbackX) < 8) p.knockbackX = 0;
            if (p.vx < -1) p.facingLeft = true;
            else if (p.vx > 1) p.facingLeft = false;
            p.x = clamp(p.x + p.vx * DT, PLAYER_HALF_WIDTH, WORLD_WIDTH - PLAYER_HALF_WIDTH);

            if (p.jumpRequested && p.grounded) {
                p.vy = JUMP_VELOCITY;
                p.grounded = false;
            }
            p.jumpRequested = false;

            boolean usingFlight = p.flying && p.energy > 0;
            if (usingFlight) {
                p.vy = -BOOST_UP_SPEED;
                p.energy = Math.max(0, p.energy - ENERGY_DRAIN_PER_SEC * DT);
                if (p.energy <= 0) p.flying = false;
            } else {
                p.flying = false;
                p.vy = Math.min(MAX_FALL_SPEED, p.vy + GRAVITY * DT);
            }

            double nextY = p.y + p.vy * DT;
            p.grounded = false;
            // One-way platforms: land only when crossing a platform top while descending.
            if (p.vy >= 0) {
                Platform landing = null;
                for (Platform platform : platforms) {
                    boolean overlapsX = p.x + PLAYER_HALF_WIDTH > platform.x1
                            && p.x - PLAYER_HALF_WIDTH < platform.x2;
                    if (overlapsX && oldY <= platform.top + 1 && nextY >= platform.top) {
                        if (landing == null || platform.top < landing.top) landing = platform;
                    }
                }
                if (landing != null) {
                    nextY = landing.top;
                    p.vy = 0;
                    p.grounded = true;
                }
            }
            p.y = nextY;
            // Booster energy recharges ONLY while the player is physically grounded.
            if (p.grounded && !p.flying) {
                p.energy = Math.min(MAX_ENERGY, p.energy + ENERGY_REGEN_PER_SEC * DT);
            }

            if (p.y > WORLD_HEIGHT + 100) {
                if (p.carrying != null) dropCarriedFlag(p);
                respawn(p);
            }
            p.y = clamp(p.y, PLAYER_HEIGHT, WORLD_HEIGHT + 100);
        }

        updateProjectiles();

        // Flags follow their carriers.
        if (redFlag.carrierId != -1) {
            Player carrier = players.get(redFlag.carrierId);
            if (carrier == null) resetFlag(redFlag);
            else { redFlag.x = carrier.x; redFlag.y = carrier.y - 25; }
        }
        if (blueFlag.carrierId != -1) {
            Player carrier = players.get(blueFlag.carrierId);
            if (carrier == null) resetFlag(blueFlag);
            else { blueFlag.x = carrier.x; blueFlag.y = carrier.y - 25; }
        }

        for (Player p : players.values()) {
            if (redFlag.carrierId == -1 && "BLUE".equals(p.team)
                    && distance(p.x, p.y - PLAYER_HEIGHT / 2, redFlag.x, redFlag.y - 12) < 38) {
                redFlag.carrierId = p.id; p.carrying = "RED";
            }
            if (blueFlag.carrierId == -1 && "RED".equals(p.team)
                    && distance(p.x, p.y - PLAYER_HEIGHT / 2, blueFlag.x, blueFlag.y - 12) < 38) {
                blueFlag.carrierId = p.id; p.carrying = "BLUE";
            }
        }

        for (Player p : players.values()) {
            if (p.carrying == null) continue;
            double ownBaseX = "RED".equals(p.team) ? RED_BASE_X : BLUE_BASE_X;
            boolean atOwnBase = Math.abs(p.x - ownBaseX) < 65 && Math.abs(p.y - BASE_FEET_Y) < 90;
            boolean ownFlagHome = "RED".equals(p.team) ? redFlag.carrierId == -1 : blueFlag.carrierId == -1;
            if (!atOwnBase || !ownFlagHome) continue;
            if ("RED".equals(p.team)) { redScore++; resetFlag(blueFlag); }
            else { blueScore++; resetFlag(redFlag); }
            p.carrying = null;
            respawn(p);
        }

        // Players may pass one another freely. Touching an enemy no longer
        // resets the player or returns a carried flag; combat handles elimination.
    }

    private static void updateProjectiles() {
        Iterator<Projectile> it = projectiles.iterator();
        while (it.hasNext()) {
            Projectile shot = it.next();
            double oldX=shot.x, oldY=shot.y;
            shot.x += shot.vx * DT;
            shot.y += shot.vy * DT;
            shot.life -= DT;
            boolean remove = shot.life <= 0 || shot.x < 0 || shot.x > WORLD_WIDTH
                    || shot.y < 0 || shot.y > WORLD_HEIGHT;
            // Projectiles collide with the same solid platform tops as the players.
            if (!remove) {
                for (Platform platform : platforms) {
                    if (shot.x >= platform.x1 && shot.x <= platform.x2
                            && oldY <= platform.top && shot.y >= platform.top) {
                        remove=true; break;
                    }
                }
            }
            if (!remove) {
                for (Player target : players.values()) {
                    if (target.id == shot.ownerId || target.health <= 0
                            || Objects.equals(target.team, shot.team)
                            || System.currentTimeMillis() < target.invulnerableUntil) continue;
                    double targetCenterY = target.y - PLAYER_HEIGHT * 0.55;
                    if (Math.abs(shot.x-target.x) < PLAYER_HALF_WIDTH + PROJECTILE_RADIUS
                            && Math.abs(shot.y-targetCenterY) < PLAYER_HEIGHT * 0.55 + PROJECTILE_RADIUS) {
                        damagePlayer(target, shot.damage, Math.signum(shot.vx) * 230.0);
                        remove=true; break;
                    }
                }
            }
            if (remove) it.remove();
        }
    }

    private static void damagePlayer(Player target, int damage, double knockback) {
        if (target.health <= 0 || System.currentTimeMillis() < target.invulnerableUntil) return;
        target.health = Math.max(0, target.health - damage);
        target.knockbackX = knockback;
        if (target.health == 0) {
            dropCarriedFlag(target);
            target.left=target.right=target.flying=target.jumpRequested=false;
            target.vx=target.vy=target.knockbackX=0;
            target.respawnAt=System.currentTimeMillis()+RESPAWN_DELAY_MS;
        }
    }

    private static void performMelee(Player attacker, boolean blade) {
        long now=System.currentTimeMillis();
        if (attacker.health<=0 || phase!=GamePhase.PLAYING) return;
        if (blade) {
            if (now<attacker.bladeReadyAt) return;
            attacker.bladeReadyAt=now+BLADE_COOLDOWN_MS;
        } else {
            if (now<attacker.fistReadyAt) return;
            attacker.fistReadyAt=now+FIST_COOLDOWN_MS;
        }
        double range=blade?82:48;
        int damage=blade?35:25;
        double halfArc=blade?0.95:0.8;
        broadcastFx(attacker.id, blade ? "BLADE" : "FIST", attacker.aimAngle);
        for (Player target:players.values()) {
            if (target.id==attacker.id || target.health<=0 || Objects.equals(target.team,attacker.team)
                    || now<target.invulnerableUntil) continue;
            double dx=target.x-attacker.x;
            double dy=(target.y-PLAYER_HEIGHT*0.55)-(attacker.y-PLAYER_HEIGHT*0.55);
            double distance=Math.hypot(dx,dy);
            if (distance>range || distance<0.001) continue;
            double angle=Math.atan2(dy,dx);
            double difference=Math.atan2(Math.sin(angle-attacker.aimAngle),Math.cos(angle-attacker.aimAngle));
            if (Math.abs(difference)<=halfArc) damagePlayer(target,damage,Math.signum(dx)*320.0);
        }
    }

    private static void shoot(Player p) {
        long now=System.currentTimeMillis();
        if (phase!=GamePhase.PLAYING || p.health<=0 || p.reloadCompleteAt>0) return;
        if ("BLADE".equals(p.weapon)) { performMelee(p,true); return; }
        if ("SHOTGUN".equals(p.weapon)) {
            if (now<p.shotgunReadyAt) return;
            if (p.shotgunAmmo<=0) { beginReload(p); return; }
            p.shotgunAmmo--; p.shotgunReadyAt=now+SHOTGUN_COOLDOWN_MS;
            broadcastFx(p.id,"SHOTGUN",p.aimAngle);
            for (int i=0;i<5;i++) {
                double spread=(i-2)*0.13;
                spawnProjectile(p,"SHOTGUN",p.aimAngle+spread,SHOTGUN_SPEED,8);
            }
        } else {
            if (now<p.pistolReadyAt) return;
            if (p.pistolAmmo<=0) { beginReload(p); return; }
            p.pistolAmmo--; p.pistolReadyAt=now+PISTOL_COOLDOWN_MS;
            broadcastFx(p.id,"PISTOL",p.aimAngle);
            spawnProjectile(p,"PISTOL",p.aimAngle,PISTOL_SPEED,15);
        }
    }

    private static void broadcastFx(int playerId,String type,double angle) {
        String msg=String.format(Locale.ROOT,"FX %d %s %.4f\n",playerId,type,angle);
        for (ClientHandler h:handlers.values()) h.send(msg);
    }

    private static void spawnProjectile(Player p,String weapon,double angle,double speed,int damage) {
        double x=p.x+Math.cos(angle)*22;
        double y=p.y-PLAYER_HEIGHT*0.62+Math.sin(angle)*22;
        projectiles.add(new Projectile(nextProjectileId++,p.id,p.team,weapon,x,y,
                Math.cos(angle)*speed,Math.sin(angle)*speed,damage));
    }

    private static void beginReload(Player p) {
        if (p.reloadCompleteAt>0) return;
        if ("PISTOL".equals(p.weapon) && p.pistolAmmo<12 && p.pistolReserve>0)
            p.reloadCompleteAt=System.currentTimeMillis()+PISTOL_RELOAD_MS;
        else if ("SHOTGUN".equals(p.weapon) && p.shotgunAmmo<5 && p.shotgunReserve>0)
            p.reloadCompleteAt=System.currentTimeMillis()+SHOTGUN_RELOAD_MS;
    }

    private static void finishReload(Player p) {
        if ("PISTOL".equals(p.weapon)) {
            int needed=12-p.pistolAmmo, take=Math.min(needed,p.pistolReserve);
            p.pistolAmmo+=take; p.pistolReserve-=take;
        } else if ("SHOTGUN".equals(p.weapon)) {
            int needed=5-p.shotgunAmmo, take=Math.min(needed,p.shotgunReserve);
            p.shotgunAmmo+=take; p.shotgunReserve-=take;
        }
        p.reloadCompleteAt=0;
    }

    private static void dropCarriedFlag(Player p) {
        if ("RED".equals(p.carrying)) resetFlag(redFlag);
        if ("BLUE".equals(p.carrying)) resetFlag(blueFlag);
        p.carrying = null;
    }
    private static void resetFlag(Flag f) { f.carrierId=-1; f.x=f.homeX; f.y=f.homeY; }
    private static void respawn(Player p) {
        p.x = "RED".equals(p.team) ? RED_BASE_X : BLUE_BASE_X;
        p.y = BASE_FEET_Y; p.vx=0; p.vy=0; p.knockbackX=0; p.grounded=false; p.flying=false; p.energy=MAX_ENERGY;
        p.health=MAX_HEALTH; p.respawnAt=0; p.invulnerableUntil=System.currentTimeMillis()+SPAWN_PROTECTION_MS;
    }
    private static double distance(double x1,double y1,double x2,double y2) { return Math.hypot(x1-x2,y1-y2); }
    private static double clamp(double v,double lo,double hi) { return Math.max(lo,Math.min(hi,v)); }

    private static void broadcastState() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("PHASE ").append(phase.name()).append('\n');
        sb.append("HOST ").append(hostId).append('\n');
        sb.append("STATE ").append(redScore).append(' ').append(blueScore).append('\n');
        sb.append("FLAG RED ").append((int)redFlag.x).append(' ').append((int)redFlag.y).append(' ').append(redFlag.carrierId).append('\n');
        sb.append("FLAG BLUE ").append((int)blueFlag.x).append(' ').append((int)blueFlag.y).append(' ').append(blueFlag.carrierId).append('\n');
        sb.append("PROJECTILES\n");
        for (Projectile shot : projectiles) {
            sb.append("PROJECTILE ").append(shot.id).append(' ').append(shot.ownerId).append(' ')
              .append(shot.weapon).append(' ').append((int)shot.x).append(' ').append((int)shot.y).append(' ')
              .append((int)shot.vx).append(' ').append((int)shot.vy).append('\n');
        }
        for (Player p : players.values()) {
            sb.append("PLAYER ").append(p.id).append(' ').append(p.name).append(' ').append(p.team).append(' ')
              .append(p.ready ? "READY" : "NOT_READY").append(' ').append(p.host ? "HOST" : "PLAYER").append(' ')
              .append((int)Math.round(p.x)).append(' ').append((int)Math.round(p.y)).append(' ')
              .append(p.carrying == null ? "-" : p.carrying).append(' ')
              .append((int)Math.round(p.vx)).append(' ').append((int)Math.round(p.vy)).append(' ')
              .append(p.grounded ? 1 : 0).append(' ').append(p.flying ? 1 : 0).append(' ')
              .append((int)Math.round(p.energy)).append(' ')
              .append(p.health).append(' ').append(p.weapon).append(' ')
              .append("PISTOL".equals(p.weapon) ? p.pistolAmmo : ("SHOTGUN".equals(p.weapon) ? p.shotgunAmmo : -1)).append(' ')
              .append("PISTOL".equals(p.weapon) ? p.pistolReserve : ("SHOTGUN".equals(p.weapon) ? p.shotgunReserve : -1)).append(' ')
              .append(String.format(Locale.ROOT,"%.4f",p.aimAngle)).append(' ')
              .append(p.reloadCompleteAt>System.currentTimeMillis()?1:0).append('\n');
        }
        sb.append("END\n");
        String message=sb.toString();
        for (ClientHandler h : handlers.values()) h.send(message);
    }

    private static void broadcastChat(String message) {
        String msg="CHAT "+message.replace('\n',' ').replace('\r',' ')+'\n';
        for (ClientHandler h : handlers.values()) h.send(msg);
    }
    private static boolean allReady() {
        if (players.size()<2 || redCount==0 || blueCount==0) return false;
        for (Player p:players.values()) if (!p.ready) return false;
        return true;
    }

    static class ClientHandler implements Runnable {
        final Socket socket; final boolean hostEligible; BufferedReader in; PrintWriter out; int id=-1; Player player;
        ClientHandler(Socket socket, boolean hostEligible) { this.socket=socket; this.hostEligible=hostEligible; }
        void send(String msg) { if (out!=null) { out.print(msg); out.flush(); } }
        @Override public void run() {
            try {
                in=new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out=new PrintWriter(socket.getOutputStream(),true);
                String line=in.readLine();
                if (line==null || !line.startsWith("JOIN ")) return;
                String name=line.substring(5).trim().replace(' ','_').replace('\n','_').replace('\r','_');
                if (name.isEmpty()) name="Player";
                synchronized(lock) {
                    if (phase!=GamePhase.LOBBY) { out.println("ERROR MATCH_STARTED"); return; }
                    id=nextId++; player=new Player(); player.id=id; player.name=name;
                    player.team=redCount<=blueCount ? "RED" : "BLUE";
                    if ("RED".equals(player.team)) redCount++; else blueCount++;
                    // The first TCP connection is the host connection. Handler threads can
                    // start out of order, so do not accidentally promote a later joiner.
                    if (hostEligible || (hostId==-1 && !hostConnectionPending)) {
                        hostId=id; player.host=true; hostConnectionPending=false;
                    }
                    respawn(player); players.put(id,player); handlers.put(id,this);
                }
                out.println("WELCOME "+id+" "+player.team);
                broadcastChat(player.name+" joined the lobby.");
                while ((line=in.readLine())!=null) handleCommand(line);
            } catch (IOException ignored) {
                // The peer disconnected.
            } finally {
                synchronized(lock) {
                    if (id!=-1) {
                        Player removed=players.remove(id);
                        if (removed!=null) {
                            if ("RED".equals(removed.team)) redCount--; else blueCount--;
                            if (redFlag.carrierId==id) resetFlag(redFlag);
                            if (blueFlag.carrierId==id) resetFlag(blueFlag);
                        }
                        handlers.remove(id);
                        if (id==hostId) {
                            hostId=-1;
                            if (!players.isEmpty()) { Player newHost=players.values().iterator().next(); newHost.host=true; hostId=newHost.id; }
                        }
                        if (hostEligible) hostConnectionPending=false;
                        if (hostId==-1 && !players.isEmpty() && !hostConnectionPending) {
                            Player newHost=players.values().iterator().next();
                            newHost.host=true; hostId=newHost.id;
                        }
                        if (players.isEmpty()) { phase=GamePhase.LOBBY; hostId=-1; redScore=blueScore=0; resetFlag(redFlag); resetFlag(blueFlag); }
                    }
                }
                if (id!=-1) broadcastChat("A player left the lobby.");
                try { socket.close(); } catch (IOException ignored) {}
            }
        }

        private void handleCommand(String line) {
            synchronized(lock) {
                if (player==null) return;
                if (line.startsWith("TEAM ")) {
                    if (phase!=GamePhase.LOBBY || player.ready) return;
                    String requested=line.substring(5).trim().toUpperCase(Locale.ROOT);
                    if (!requested.equals("RED")&&!requested.equals("BLUE")||requested.equals(player.team)) return;
                    if ("RED".equals(player.team)) redCount--; else blueCount--;
                    player.team=requested; if ("RED".equals(requested)) redCount++; else blueCount++;
                    respawn(player);
                } else if (line.equals("READY")) {
                    if (phase==GamePhase.LOBBY) player.ready=true;
                } else if (line.equals("UNREADY")) {
                    if (phase==GamePhase.LOBBY) player.ready=false;
                } else if (line.equals("START")) {
                    if (phase!=GamePhase.LOBBY || player.id!=hostId || !allReady()) return;
                    redScore=blueScore=0; resetFlag(redFlag); resetFlag(blueFlag);
                    projectiles.clear();
                    for (Player p:players.values()) { p.carrying=null; p.left=p.right=p.jumpRequested=p.flying=false; respawn(p); }
                    phase=GamePhase.PLAYING;
                } else if (line.startsWith("CHAT ")) {
                    String message=line.substring(5).trim().replace('\n',' ').replace('\r',' ');
                    if (message.isEmpty()) return; if (message.length()>200) message=message.substring(0,200);
                    broadcastChat(player.name+": "+message);
                } else if (line.startsWith("INPUT ")) {
                    if (phase!=GamePhase.PLAYING || player.health<=0) return;
                    String[] t=line.split(" ");
                    if (t.length==5) {
                        player.left="1".equals(t[1]);
                        player.right="1".equals(t[2]);
                        boolean jump="1".equals(t[3]);
                        player.flying="1".equals(t[4]) && player.energy>0;
                        if (jump && player.grounded) player.jumpRequested=true;
                    }
                } else if (line.startsWith("AIM ")) {
                    if (phase!=GamePhase.PLAYING || player.health<=0) return;
                    try { player.aimAngle=clamp(Double.parseDouble(line.substring(4).trim()),-Math.PI,Math.PI); }
                    catch (NumberFormatException ignored) {}
                } else if (line.equals("MELEE")) {
                    performMelee(player,false);
                } else if (line.equals("SHOOT")) {
                    shoot(player);
                } else if (line.equals("RELOAD")) {
                    if (phase==GamePhase.PLAYING) beginReload(player);
                } else if (line.startsWith("WEAPON ")) {
                    if (phase!=GamePhase.PLAYING) return;
                    String requested=line.substring(7).trim().toUpperCase(Locale.ROOT);
                    if (requested.equals("PISTOL") || requested.equals("SHOTGUN") || requested.equals("BLADE")) {
                        player.weapon=requested; player.reloadCompleteAt=0;
                    }
                }
            }
        }
        void close() { try { socket.close(); } catch (IOException ignored) {} }
    }

    public static void main(String[] args) throws Exception {
        GameServer server=new GameServer(PORT); server.start();
        System.out.println("Press Ctrl+C to stop."); Thread.currentThread().join();
    }
}
