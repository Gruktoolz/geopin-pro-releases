package mx.coberturapunto;
import org.junit.Test;
import static org.junit.Assert.*;
public class SearchPolicyTest {
 @Test public void anyFirstCharacterAndNumbersAreEligible(){for(String s:new String[]{"A","v","ñ","7","San","Valle","20 de Noviembre"})assertTrue(s,SearchPolicy.eligible(s));}
 @Test public void coordinateAndLinkPathsRemainSeparate(){for(String s:new String[]{"", " ","geo:0,0","https://maps.app.goo.gl/test","A\nB"})assertFalse(s,SearchPolicy.eligible(s));}
 @Test public void accentCaseAndPunctuationNormalize(){assertEquals("canon del rio",SearchPolicy.normalize("CAÑÓN del Río"));assertEquals("villa* font*",SearchPolicy.prefixQuery("Villa Font"));assertTrue(SearchPolicy.matches("Cañón del Río, Tijuana","canon rio"));}
 @Test public void ftsOperatorsCannotBecomeAnExpression(){assertEquals("or* near*",SearchPolicy.prefixQuery("\" OR * NEAR()"));assertEquals("",SearchPolicy.prefixQuery("*\"()"));}
 @Test public void distanceUsesBothCoordinates(){assertEquals(0,SearchPolicy.distance(32,-117,32,-117),0);assertTrue(SearchPolicy.distance(32,-117,32,-116)>.1);}
}
