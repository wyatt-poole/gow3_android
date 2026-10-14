package ro.cobrabm.fexdroid;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;

/** Vulkan drivers the player installs from a zip, beside the bundled Turnip. Each one lives in
 *  files/drivers/<id>/: the zip's libraries, driver.json (its name and version) and icd.json (the
 *  Vulkan loader's manifest, naming the library there). android.ini gpu_driver=<id> picks one;
 *  run-thor.sh then uses its icd.json, else the bundled Turnip.
 *
 *  The game is a glibc Linux process: only Linux (glibc) builds load, such as Turnip for Winlator
 *  or Mobox. Turnip for Android (adrenotools zips with meta.json, bionic) cannot. */
final class DriverManager {
    /** An installed driver: its folder name, what its zip calls it, its version (or "") and the zip's
     *  file name (or "", when unknown): the download list matches releases by it. */
    static final class Driver {
        final String id, name, version, zip;
        Driver(String id,String name,String version,String zip) { this.id=id; this.name=name; this.version=version; this.zip=zip; }
        String title() { return version.isEmpty() ? name : name+" "+version; }
    }

    private static final long MAX_SIZE=1024L*1024*1024;
    private final File dir, root;

    /** files: the app's files directory (its drivers/ folder and the rootfs's libraries). */
    DriverManager(File files) { dir=new File(files,"drivers"); root=new File(files,"rootfs"); }

    List<Driver> list() {
        List<Driver> out=new ArrayList<>();
        File[] folders=dir.listFiles();
        if (folders==null) return out;
        java.util.Arrays.sort(folders);
        for (File folder:folders) {
            if (folder.getName().startsWith(".") || !new File(folder,"icd.json").isFile()) continue;
            String name=folder.getName(), version="", zip="";
            try {
                JSONObject about=new JSONObject(new String(Files.readAllBytes(new File(folder,"driver.json").toPath()),StandardCharsets.UTF_8));
                name=about.optString("name",name); version=about.optString("version",""); zip=about.optString("zip","");
            } catch (Exception ignored) {}
            out.add(new Driver(folder.getName(),name,version,zip));
        }
        return out;
    }
    Driver find(String id) {
        if (id==null || id.isEmpty()) return null;
        for (Driver d:list()) if (d.id.equals(id)) return d;
        return null;
    }
    void delete(String id) throws IOException { if (valid(id)) deleteTree(new File(dir,id)); }

    /** Unpacks a driver zip (named zipName, for the driver's name when it has none), checks it and
     *  installs it. Returns the new driver; an IOException's message says what is wrong with the zip. */
    Driver install(InputStream in,String zipName) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create "+dir);
        File staging=new File(dir,".installing");
        deleteTree(staging);
        if (!staging.mkdirs()) throw new IOException("Cannot create "+staging);
        try {
            JSONObject meta=null, icd=null;
            List<String> libraries=new ArrayList<>();
            long total=0;
            byte[] buffer=new byte[1<<16];
            try (ZipInputStream zip=new ZipInputStream(in)) {
                for (ZipEntry e; (e=zip.getNextEntry())!=null;) {
                    if (e.isDirectory()) continue;
                    // Only the file name: folders inside the zip (usr/lib/...) are flattened.
                    String path=e.getName().replace('\\','/');
                    String name=path.substring(path.lastIndexOf('/')+1);
                    boolean library=name.matches("lib[A-Za-z0-9._+-]*\\.so(\\.[0-9]+)*");
                    boolean json=name.endsWith(".json") && (name.equals("meta.json") || name.contains("icd"));
                    if (!library && !json) continue;
                    if (json) {
                        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
                        for (int n; (n=zip.read(buffer))>0;) { bytes.write(buffer,0,n); if (bytes.size()>1<<20) throw new IOException(name+" is too large"); }
                        try {
                            JSONObject o=new JSONObject(bytes.toString("UTF-8"));
                            if (name.equals("meta.json")) meta=o; else if (o.has("ICD")) icd=o.getJSONObject("ICD");
                        } catch (org.json.JSONException ex) { throw new IOException(name+" is not valid JSON"); }
                        continue;
                    }
                    if (libraries.contains(name)) continue;
                    try (FileOutputStream out=new FileOutputStream(new File(staging,name))) {
                        for (int n; (n=zip.read(buffer))>0;) {
                            total+=n;
                            if (total>MAX_SIZE) throw new IOException("The zip's libraries are larger than 1 GB");
                            out.write(buffer,0,n);
                        }
                    }
                    libraries.add(name);
                }
            }
            if (libraries.isEmpty()) throw new IOException("The zip has no driver library (libvulkan_*.so).");

            String library=pickLibrary(libraries,icd,meta);
            if (library==null) throw new IOException("The zip has no Vulkan driver library (libvulkan_*.so). Found: "
                +String.join(", ",libraries));
            String problem=checkLibrary(new File(staging,library),staging);
            if (problem!=null) throw new IOException(library+" "+problem);
            // The zip's own checksum of the library (our Turnip builds write one): catches a damaged or swapped file.
            String expected=meta!=null && !meta.isNull("sha256") ? meta.optString("sha256").trim() : "";
            if (!expected.isEmpty() && !expected.equalsIgnoreCase(sha256(new File(staging,library))))
                throw new IOException("The driver library's checksum does not match its meta.json");

            String name=meta!=null && !meta.optString("name").isEmpty() ? meta.optString("name")
                : zipName.replaceAll("(?i)\\.zip$","");
            String version=meta!=null ? meta.optString("driverVersion",meta.optString("packageVersion","")) : "";
            String id=uniqueId(name);
            File target=new File(dir,id);
            String apiVersion=icd!=null && icd.optString("api_version").matches("[0-9]+\\.[0-9]+(\\.[0-9]+)*")
                ? icd.optString("api_version") : "1.3.0";
            JSONObject manifest=new JSONObject(), entry=new JSONObject(), about=new JSONObject();
            try {
                entry.put("library_path",new File(target,library).getAbsolutePath());
                entry.put("library_arch","64"); entry.put("api_version",apiVersion);
                manifest.put("file_format_version","1.0.1"); manifest.put("ICD",entry);
                about.put("name",name); about.put("version",version); about.put("library",library);
                about.put("zip",zipName);
            } catch (org.json.JSONException ex) { throw new IOException(ex.toString()); }
            Files.write(new File(staging,"icd.json").toPath(),manifest.toString(2).getBytes(StandardCharsets.UTF_8));
            Files.write(new File(staging,"driver.json").toPath(),about.toString(2).getBytes(StandardCharsets.UTF_8));
            if (!staging.renameTo(target)) throw new IOException("Cannot move the driver to "+target);
            return new Driver(id,name,version,zipName);
        } catch (org.json.JSONException ex) {
            throw new IOException(ex.toString());
        } finally {
            deleteTree(staging);
        }
    }

    private static String sha256(File file) throws IOException {
        try (InputStream in=new java.io.FileInputStream(file)) {
            java.security.MessageDigest hash=java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer=new byte[1<<16];
            for (int n; (n=in.read(buffer))>0;) hash.update(buffer,0,n);
            StringBuilder hex=new StringBuilder();
            for (byte b:hash.digest()) hex.append(String.format(Locale.ROOT,"%02x",b&0xff));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IOException(ex.toString()); }
    }

    /** The library the Vulkan loader loads: the one the zip's own manifest or meta.json names, else
     *  libvulkan_freedreno*.so, else the only libvulkan*.so. */
    private static String pickLibrary(List<String> libraries,JSONObject icd,JSONObject meta) {
        for (String named:new String[]{icd==null ? null : icd.optString("library_path",null),
                                       meta==null ? null : meta.optString("libraryName",null)}) {
            if (named==null) continue;
            String base=named.substring(named.lastIndexOf('/')+1);
            if (libraries.contains(base)) return base;
        }
        for (String name:libraries) if (name.startsWith("libvulkan_freedreno")) return name;
        String found=null;
        for (String name:libraries) if (name.startsWith("libvulkan")) { if (found!=null) return null; found=name; }
        return found;
    }

    /** Null when the library can load here: a 64-bit ARM glibc shared library that is a Vulkan
     *  driver, whose libraries are in the rootfs or the zip. Else what is wrong with it. */
    String checkLibrary(File so,File beside) throws IOException {
        try (RandomAccessFile f=new RandomAccessFile(so,"r"); FileChannel channel=f.getChannel()) {
            if (channel.size()<64 || channel.size()>Integer.MAX_VALUE) return "is not a driver library";
            ByteBuffer b=channel.map(FileChannel.MapMode.READ_ONLY,0,channel.size()).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt(0)!=0x464c457f) return "is not a Linux library (ELF)";
            if (b.get(4)!=2 || b.get(5)!=1) return "is not a 64-bit library";
            if (b.getShort(18)!=183) return "is not built for 64-bit ARM (AArch64)";
            if (b.getShort(16)!=3) return "is not a shared library";
            // PT_LOAD maps addresses to file offsets; PT_DYNAMIC has DT_NEEDED and the string table.
            long phoff=b.getLong(32); int size=b.getShort(54)&0xffff, count=b.getShort(56)&0xffff;
            List<long[]> loads=new ArrayList<>();
            long dynamic=-1, dynamicSize=0;
            for (int i=0;i<count;++i) {
                int p=(int)(phoff+(long)i*size);
                if (p<0 || p+56>b.limit()) return "is damaged (program headers)";
                int type=b.getInt(p);
                if (type==1) loads.add(new long[]{b.getLong(p+16),b.getLong(p+8),b.getLong(p+32)});
                if (type==2) { dynamic=b.getLong(p+8); dynamicSize=b.getLong(p+32); }
            }
            if (dynamic<0) return "has no dynamic section";
            long strtab=-1, strsz=0; List<Long> needed=new ArrayList<>();
            for (long d=dynamic; d+16<=dynamic+dynamicSize && d+16<=b.limit(); d+=16) {
                long tag=b.getLong((int)d), value=b.getLong((int)d+8);
                if (tag==0) break;
                if (tag==1) needed.add(value); else if (tag==5) strtab=value; else if (tag==10) strsz=value;
            }
            long strings=-1;
            for (long[] load:loads) if (strtab>=load[0] && strtab<load[0]+load[2]) strings=strtab-load[0]+load[1];
            if (strings<0 || strings+strsz>b.limit()) return "is damaged (string table)";
            List<String> libs=new ArrayList<>();
            for (long offset:needed) libs.add(cString(b,(int)(strings+offset)));
            if (libs.contains("libc.so") || libs.contains("liblog.so"))
                return "is a Turnip build for Android (bionic, e.g. an adrenotools zip). The game runs in a Linux "
                    +"(glibc) runtime: use a Linux build of the driver, such as Turnip for Winlator or Mobox.";
            if (!libs.contains("libc.so.6")) return "is not a Linux (glibc) library";
            byte[] table=new byte[(int)strsz]; b.position((int)strings); b.get(table);
            if (!new String(table,StandardCharsets.ISO_8859_1).contains("\0vk_icdGetInstanceProcAddr\0"))
                return "is not a Vulkan driver (it has no vk_icdGetInstanceProcAddr)";
            List<String> missing=new ArrayList<>();
            for (String lib:libs)
                if (!new File(beside,lib).exists() && !new File(root,"usr/lib/aarch64-linux-gnu/"+lib).exists()
                        && !new File(root,"usr/lib/"+lib).exists()) missing.add(lib);
            if (!missing.isEmpty()) return "needs libraries the runtime does not have: "+String.join(", ",missing);
            return null;
        }
    }
    private static String cString(ByteBuffer b,int at) {
        int end=at; while (end<b.limit() && b.get(end)!=0) ++end;
        byte[] bytes=new byte[end-at]; for (int i=0;i<bytes.length;++i) bytes[i]=b.get(at+i);
        return new String(bytes,StandardCharsets.UTF_8);
    }

    /** Folder names run-thor.sh accepts: letters, digits, '.', '_' and '-', not starting with '.'. */
    static boolean valid(String id) { return id!=null && id.matches("[A-Za-z0-9_-][A-Za-z0-9._-]*"); }
    private String uniqueId(String name) {
        String base=name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+","-").replaceAll("^[.-]+|-+$","");
        if (base.isEmpty()) base="driver";
        if (base.length()>48) base=base.substring(0,48);
        String id=base;
        for (int i=2; new File(dir,id).exists(); ++i) id=base+"-"+i;
        return id;
    }
    private static void deleteTree(File file) throws IOException {
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children=file.listFiles();
            if (children!=null) for (File child:children) deleteTree(child);
        }
        if (file.exists() && !file.delete()) throw new IOException("Cannot delete "+file);
    }
}
