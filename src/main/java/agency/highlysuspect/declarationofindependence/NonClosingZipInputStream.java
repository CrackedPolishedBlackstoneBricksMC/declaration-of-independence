package agency.highlysuspect.declarationofindependence;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipInputStream;

public class NonClosingZipInputStream extends ZipInputStream {
	public NonClosingZipInputStream(InputStream in) {
		super(in);
	}
	
	@Override
	public void close() throws IOException {
		inf.end();
	}
}
