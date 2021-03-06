package games.strategy.engine.framework.map.download;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.io.File;

import org.junit.jupiter.api.Test;

import games.strategy.engine.ClientFileSystemHelper;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.util.Version;

public class DownloadFileDescriptionTest extends AbstractClientSettingTestCase {

  @Test
  public void testIsMap() {
    final DownloadFileDescription testObj = new DownloadFileDescription("", "", "", new Version(0, 0),
        DownloadFileDescription.DownloadType.MAP, DownloadFileDescription.MapCategory.EXPERIMENTAL);
    assertThat(testObj.isMap(), is(true));
  }

  @Test
  public void testIsSkin() {
    final DownloadFileDescription testObj = new DownloadFileDescription("", "", "", new Version(0, 0),
        DownloadFileDescription.DownloadType.MAP_SKIN, DownloadFileDescription.MapCategory.EXPERIMENTAL);
    assertThat(testObj.isMapSkin(), is(true));
  }

  @Test
  public void testIsTool() {
    final DownloadFileDescription testObj = new DownloadFileDescription("", "", "", new Version(0, 0),
        DownloadFileDescription.DownloadType.MAP_TOOL, DownloadFileDescription.MapCategory.EXPERIMENTAL);
    assertThat(testObj.isMapTool(), is(true));

  }

  @Test
  public void testGetMapName() {
    final String mapName = "abc";
    final DownloadFileDescription testObj =
        new DownloadFileDescription("", "", mapName, new Version(0, 0), DownloadFileDescription.DownloadType.MAP,
            DownloadFileDescription.MapCategory.EXPERIMENTAL);
    assertThat(testObj.getMapName(), is(mapName));
  }

  @Test
  public void testGetMapType() {
    final DownloadFileDescription testObj =
        new DownloadFileDescription("", "", "", new Version(0, 0), DownloadFileDescription.DownloadType.MAP,
            DownloadFileDescription.MapCategory.BEST);
    assertThat(testObj.getMapCategory(), is(DownloadFileDescription.MapCategory.BEST));
  }


  @Test
  public void testGetMapFileName() {
    final String expectedFileName = "world_war_ii_revised.zip";
    String inputUrl = "https://github.com/triplea-maps/world_war_ii_revised/releases/download/0.1/" + expectedFileName;

    DownloadFileDescription testObj = testObjFromUrl(inputUrl);
    assertThat(testObj.getMapZipFileName(), is(expectedFileName));

    inputUrl = "http://abc.com/" + expectedFileName;
    testObj = testObjFromUrl(inputUrl);
    assertThat(testObj.getMapZipFileName(), is(expectedFileName));

    inputUrl = "abc.zip";
    testObj = testObjFromUrl(inputUrl);
    assertThat("Unable to parse a url, no last '/' character, return empty.", testObj.getMapZipFileName(), is(""));

  }

  private static DownloadFileDescription testObjFromUrl(final String inputUrl) {
    return new DownloadFileDescription(inputUrl, "", "", new Version(0, 0),
        DownloadFileDescription.DownloadType.MAP, DownloadFileDescription.MapCategory.EXPERIMENTAL);
  }

  @Test
  public void testGetInstallLocation() {
    String inputUrl = "http://github.com/abc.zip";
    String mapName = "123-map";
    File expected = new File(ClientFileSystemHelper.getUserMapsFolder() + File.separator + mapName + ".zip");

    mapInstallLocationTest(inputUrl, mapName, expected);

    inputUrl = "http://github.com/abc-master.zip";
    mapName = "best_map";
    expected = new File(ClientFileSystemHelper.getUserMapsFolder() + File.separator + mapName + "-master.zip");
    mapInstallLocationTest(inputUrl, mapName, expected);
  }

  private static void mapInstallLocationTest(final String inputUrl, final String mapName, final File expected) {
    final DownloadFileDescription testObj = new DownloadFileDescription(inputUrl, "", mapName, new Version(0, 0),
        DownloadFileDescription.DownloadType.MAP, DownloadFileDescription.MapCategory.EXPERIMENTAL);

    assertThat(testObj.getInstallLocation().getAbsolutePath(), is(expected.getAbsolutePath()));
  }
}
