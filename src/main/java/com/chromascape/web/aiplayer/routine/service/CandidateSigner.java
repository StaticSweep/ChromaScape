package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.contract.CanonicalJsonHasher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public class CandidateSigner {
  private final String keyId; private final PrivateKey key;
  public CandidateSigner(String keyId, Path privateKeyPath){this.keyId=keyId;this.key=load(privateKeyPath);}
  public String keyId(){return keyId;}
  public String signCandidateHash(String candidateHash){try{Signature signature=Signature.getInstance("Ed25519");signature.initSign(key);signature.update(raw(candidateHash));return Base64.getEncoder().encodeToString(signature.sign());}catch(Exception e){throw new IllegalStateException("SIGNING_KEY_UNAVAILABLE",e);}}
  public String unsignedHash(JsonNode manifest){JsonNode copy=manifest.deepCopy();if(copy.isObject()){ObjectNode object=(ObjectNode)copy;JsonNode candidate=object.get("candidate");if(candidate!=null&&candidate.isObject()){((ObjectNode)candidate).remove("candidate_hash");
      ((ObjectNode)candidate).remove("candidate_id");
      ((ObjectNode)candidate).remove("signature");}object.remove("candidate_hash");object.remove("candidate_id");object.remove("signature");}return CanonicalJsonHasher.hash(copy,null);}
  private static byte[] raw(String hash){if(!CanonicalJsonHasher.isSha256(hash))throw new IllegalArgumentException("candidate hash format");return java.util.HexFormat.of().parseHex(hash.substring(7));}
  private static PrivateKey load(Path path){try{String pem=Files.readString(path).replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replaceAll("\\s","");return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));}catch(Exception e){throw new IllegalStateException("SIGNING_KEY_UNAVAILABLE",e);}}
}
