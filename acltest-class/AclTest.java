import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.io.*;

public class AclTest {
  public static void main(String[] args) throws Exception {
    Path f = Files.createTempFile("buildergoal-acltest-", ".json");
    Files.writeString(f, "{\"clientArgs\":[\"--world=360\"]}");
    AclFileAttributeView acl = Files.getFileAttributeView(f, AclFileAttributeView.class);
    System.out.println("OWNER=" + acl.getOwner());
    System.out.println("USERNAME=" + System.getProperty("user.name"));
    UserPrincipal current = null;
    try {
      current = FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
      System.out.println("CURRENT_PRINCIPAL=" + current);
    } catch (Exception e) { System.out.println("LOOKUP_FAIL=" + e); }
    EnumSet<AclEntryPermission> p = EnumSet.of(
      AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA,
      AclEntryPermission.READ_ATTRIBUTES, AclEntryPermission.WRITE_ATTRIBUTES,
      AclEntryPermission.DELETE, AclEntryPermission.READ_ACL, AclEntryPermission.WRITE_ACL,
      AclEntryPermission.SYNCHRONIZE);
    List<AclEntry> entries = new ArrayList<>();
    entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner()).setPermissions(p).build());
    if (current != null) entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(current).setPermissions(p).build());
    acl.setAcl(entries);
    System.out.println("=== AFTER ===");
    for (AclEntry e2 : acl.getAcl()) { System.out.println("ACE: " + e2.principal() + " " + e2.permissions()); }
    System.out.println("FILE=" + f);
  }
}
