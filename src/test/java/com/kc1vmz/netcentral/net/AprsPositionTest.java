package com.kc1vmz.netcentral.net;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class AprsPositionTest {
 @Test void ambiguityLevelsAndHemispheres() {
  String[] lat={"4258.12N","4258.1 N","4258.  N","425 .  N","42  .  N"};
  String[] lon={"07127.12W","07127.1 W","07127.  W","0712 .  W","071  .  W"};
  double[] minutes={58.12,58.15,58.5,55,30};
  for(int i=0;i<5;i++) {
   var p=AprsPosition.parse("!"+lat[i]+"/"+lon[i]+">");
   assertThat(p).isNotNull();assertThat(p.ambiguity()).isEqualTo(i);
   assertThat(p.latitude()).isCloseTo(42+minutes[i]/60,within(.000001));
   assertThat(p.longitude()).isNegative();assertThat(p.rawLatitude()).isEqualTo(lat[i]);
   var south=AprsPosition.parse("!"+lat[i].replace('N','S')+"/"+lon[i].replace('W','E')+">");
   assertThat(south.latitude()).isEqualTo(-p.latitude());assertThat(south.longitude()).isEqualTo(-p.longitude());
   assertThat(p.uncertaintyMeters()>0).isEqualTo(i>0);
  }
 }
 @Test void latitudeAmbiguityAppliesToLongitude() {
  assertThat(AprsPosition.parse("!4258.  N/07127.99W>").longitude()).isCloseTo(-(71+27.5/60),within(.000001));
 }
 @Test void malformedCoordinatesAreRejected() {
  for(String value:new String[]{"!4258.  N/07127. W>","!4258.  N/0712 .  W>","!42 8.12N/07127.12W>","!4260.12N/07127.12W>","!9058.12N/07127.12W>","!4258.12N/18127.12W>"})
   assertThat(AprsPosition.parse(value)).as(value).isNull();
 }
}
