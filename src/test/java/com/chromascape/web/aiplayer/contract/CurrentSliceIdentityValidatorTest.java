package com.chromascape.web.aiplayer.contract;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CurrentSliceIdentityValidatorTest {
  private static final String HASH="sha256:0000000000000000000000000000000000000000000000000000000000000000";
  private CurrentSliceIdentity base(String kind){
    CurrentSliceIdentity.Run run="MICRO6_MANAGED".equals(kind)?new CurrentSliceIdentity.Run("run_1","attempt_1",1,"executor_1"):null;
    return new CurrentSliceIdentity("current-slice-identity/v1",new CurrentSliceIdentity.Task("FISHING_TRAINING",HASH,"profile",HASH,HASH),run,new CurrentSliceIdentity.Client("client",kind,"build",HASH,"MICRO6_MANAGED".equals(kind)?"micro6-builder/v2":"runelite-capture/v1","MICRO6_MANAGED".equals(kind)?"MICRO6":"RUNELITE"),null,null,null,null,null,false);
  }
  @Test void acceptsMicro6Identity(){assertDoesNotThrow(()->base("MICRO6_MANAGED").validate());}
  @Test void acceptsRuneLiteWithoutExecutorFields(){assertDoesNotThrow(()->base("RUNELITE_CAPTURE").validate());}
  @Test void rejectsRequestedControl(){CurrentSliceIdentity identity=base("RUNELITE_CAPTURE");identity=new CurrentSliceIdentity(identity.schema(),identity.task(),identity.run(),identity.client(),identity.capture(),identity.source(),identity.graph(),identity.model(),identity.candidate(),true);assertThrows(CurrentSliceContractException.class,identity::validate);}
}
