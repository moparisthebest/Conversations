package eu.siacs.conversations.xml;

import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.*;

import eu.siacs.conversations.Config;

public class XmlReader {
	private XmlPullParser parser;
	private PowerManager.WakeLock wakeLock;
	private InputStream is;
	private boolean inputSet = false;

	public XmlReader(WakeLock wakeLock) {
		this.parser = Xml.newPullParser();
		try {
			this.parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
		} catch (XmlPullParserException e) {
			Log.d(Config.LOGTAG, "error setting namespace feature on parser");
		}
		this.wakeLock = wakeLock;
	}

	public XmlReader(final Reader input) throws IOException {
		this((WakeLock) null);
		if(input == null)
			throw new IOException();
		this.inputSet = true;
		try {
			parser.setInput(input);
		} catch (XmlPullParserException e) {
			throw new IOException("error resetting parser");
		}
	}

	public void setInputStream(InputStream inputStream) throws IOException {
		if (inputStream == null) {
			throw new IOException();
		}
		this.is = inputStream;
		this.inputSet = true;
		try {
			parser.setInput(new InputStreamReader(this.is));
		} catch (XmlPullParserException e) {
			throw new IOException("error resetting parser");
		}
	}

	public void reset() throws IOException {
		if (this.is == null) {
			throw new IOException();
		}
		try {
			parser.setInput(new InputStreamReader(this.is));
		} catch (XmlPullParserException e) {
			throw new IOException("error resetting parser");
		}
	}

	public Tag readTag() throws XmlPullParserException, IOException {
		if (wakeLock != null && wakeLock.isHeld()) {
			try {
				wakeLock.release();
			} catch (RuntimeException re) {
				Log.d(Config.LOGTAG,"runtime exception releasing wakelock before reading tag "+re.getMessage());
			}
		}
		try {
			while (inputSet && parser.next() != XmlPullParser.END_DOCUMENT) {
				if(wakeLock != null) {
					wakeLock.acquire();
				}
				if (parser.getEventType() == XmlPullParser.START_TAG) {
					Tag tag = Tag.start(parser.getName());
					final String xmlns = parser.getNamespace();
					for (int i = 0; i < parser.getAttributeCount(); ++i) {
						final String prefix = parser.getAttributePrefix(i);
						String name;
						if (prefix != null && !prefix.isEmpty()) {
							name = prefix+":"+parser.getAttributeName(i);
						} else {
							name = parser.getAttributeName(i);
						}
						tag.setAttribute(name,parser.getAttributeValue(i));
					}
					if (xmlns != null) {
						tag.setAttribute("xmlns", xmlns);
					}
					return tag;
				} else if (parser.getEventType() == XmlPullParser.END_TAG) {
					return Tag.end(parser.getName());
				} else if (parser.getEventType() == XmlPullParser.TEXT) {
					return Tag.no(parser.getText());
				}
			}

		} catch (Throwable throwable) {
			throw new IOException("xml parser mishandled "+throwable.getClass().getSimpleName()+"("+throwable.getMessage()+")", throwable);
		} finally {
			if (wakeLock != null && wakeLock.isHeld()) {
				try {
					wakeLock.release();
				} catch (RuntimeException re) {
					Log.d(Config.LOGTAG,"runtime exception releasing wakelock after exception "+re.getMessage());
				}
			}
		}
		return null;
	}

	public Element readElement(Tag currentTag) throws XmlPullParserException,
			IOException {
		Element element = new Element(currentTag.getName());
		element.setAttributes(currentTag.getAttributes());
		Tag nextTag = this.readTag();
		if (nextTag == null) {
			throw new IOException("interrupted mid tag");
		}
		if (nextTag.isNo()) {
			element.setContent(nextTag.getName());
			nextTag = this.readTag();
			if (nextTag == null) {
				throw new IOException("interrupted mid tag");
			}
		}
		while (!nextTag.isEnd(element.getName())) {
			if (!nextTag.isNo()) {
				Element child = this.readElement(nextTag);
				element.addChild(child);
			}
			nextTag = this.readTag();
			if (nextTag == null) {
				throw new IOException("interrupted mid tag");
			}
		}
		return element;
	}

	public Element nextWholeElement() throws XmlPullParserException, IOException {
		final Tag currentTag = this.readTag();
		if(currentTag == null)
			return null;
		final Element element = new Element(currentTag.name);
		int tagCount = 1;
		element.setAttributes(currentTag.getAttributes());
		Tag nextTag = this.readTag();
		if (nextTag == null) {
			throw new IOException("interrupted mid tag");
		}
		while (!nextTag.isEnd(element.getName()) && --tagCount < 1) {
			if (nextTag.isStart(element.getName()))
				++tagCount;
			if (!nextTag.isNo()) {
				element.addChild(this.readElement(nextTag));
			}
			nextTag = this.readTag();
			if (nextTag == null) {
				throw new IOException("interrupted mid tag");
			}
		}
		return element;
	}
}
