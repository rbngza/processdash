// Copyright (C) 2018 Tuma Solutions, LLC
// Process Dashboard - Data Automation Tool for high-maturity processes
//
// This program is free software; you can redistribute it and/or
// modify it under the terms of the GNU General Public License
// as published by the Free Software Foundation; either version 3
// of the License, or (at your option) any later version.
//
// Additional permissions also apply; see the README-license.txt
// file in the project root directory for more information.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, see <http://www.gnu.org/licenses/>.
//
// The author(s) may be contacted at:
//     processdash@tuma-solutions.com
//     processdash-devel@lists.sourceforge.net

package teamdash.sync;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xmlpull.v1.XmlSerializer;

import net.sourceforge.processdash.tool.export.impl.ArchiveMetricsXmlConstants;
import net.sourceforge.processdash.tool.export.mgr.ExportFileEntry;
import net.sourceforge.processdash.util.FileUtils;
import net.sourceforge.processdash.util.RobustFileOutputStream;
import net.sourceforge.processdash.util.StringUtils;
import net.sourceforge.processdash.util.XMLUtils;

public class SyncDataFile implements ArchiveMetricsXmlConstants {

    private TeamProjectDataTarget target;

    private File file;

    private ZipFile zip;

    private SyncMetadata syncMetadata;

    public SyncDataFile(TeamProjectDataTarget target, String filename) {
        this.target = target;
        this.file = new File(target.getDirectory(), filename);
    }

    public void dispose() throws IOException {
        if (zip != null) {
            zip.close();
            zip = null;
        }
        syncMetadata = null;
    }

    public void saveChanges() throws IOException {
        if (syncMetadata.isChanged()) {
            saveFile();
            target.saveSyncData(file.getName());
            syncMetadata.clearChanged();
        }
    }

    public InputStream openEntry(String entryName) throws IOException {
        // if the file does not exist, it doesn't have any entries
        if (!file.isFile())
            return null;

        // create a ZipFile object as needed
        if (zip == null)
            zip = new ZipFile(file);

        // find and return the named entry
        ZipEntry entry = zip.getEntry(entryName);
        return (entry == null ? null : zip.getInputStream(entry));
    }

    public SyncMetadata getMetadata() throws IOException {
        // if the metadata hasn't been loaded yet, do so now
        if (syncMetadata == null)
            syncMetadata = loadMetadata();
        return syncMetadata;
    }

    private SyncMetadata loadMetadata() throws IOException {
        SyncMetadata result = new SyncMetadata();
        InputStream in = openEntry(METADATA_ENTRY_NAME);
        if (in != null) {
            result.loadFromXML(in);
            in.close();
        }
        return result;
    }

    private void saveFile() throws IOException {
        // load information from the existing file, as applicable
        List<ExportFileEntry> fileEntries = readExistingManifestEntries();
        getMetadata();

        // open a stream to write ZIP data to the file
        RobustFileOutputStream out = new RobustFileOutputStream(file);
        ZipOutputStream zipOut = new ZipOutputStream(
                new BufferedOutputStream(out));

        // write the sync metadata file
        writeSyncMetadataFile(zipOut);

        // copy other file entries from src to dest
        copyExistingFileEntries(fileEntries, zipOut);

        // write the manifest file
        writeManifest(zipOut, fileEntries);

        // finalize the ZIP file
        if (zip != null) {
            zip.close();
            zip = null;
        }
        zipOut.finish();
        zipOut.close();
    }


    private List<ExportFileEntry> readExistingManifestEntries()
            throws IOException {
        if (!file.isFile())
            return Collections.EMPTY_LIST;

        // read the manifest from the existing file
        Element manifest;
        try {
            manifest = XMLUtils.parse(openEntry(MANIFEST_FILE_NAME))
                    .getDocumentElement();
        } catch (SAXException e) {
            throw new IOException(e);
        }

        // read the file entries from the manifest
        List<ExportFileEntry> entries = new ArrayList<ExportFileEntry>();
        for (Element xml : XMLUtils.getChildElements(manifest)) {
            if (FILE_ELEM.equals(xml.getTagName())) {
                ExportFileEntry efe = new ExportFileEntry(xml);
                if (!METADATA_ENTRY_NAME.equals(efe.getFilename()))
                    entries.add(efe);
            }
        }
        return entries;
    }

    private void writeSyncMetadataFile(ZipOutputStream zipOut)
            throws IOException {
        // save the sync metadata file
        zipOut.putNextEntry(new ZipEntry(METADATA_ENTRY_NAME));
        syncMetadata.storeToXML(zipOut, null);
        zipOut.closeEntry();
    }

    private void copyExistingFileEntries(List<ExportFileEntry> fileEntries,
            ZipOutputStream zipOut) throws IOException {
        // copy other data files from the old ZIP into the new ZIP
        for (ExportFileEntry efe : fileEntries) {
            String entryName = efe.getFilename();
            zipOut.putNextEntry(new ZipEntry(entryName));
            InputStream src = openEntry(entryName);
            FileUtils.copyFile(src, zipOut);
            src.close();
            zipOut.closeEntry();
        }
    }

    private void writeManifest(ZipOutputStream zipOut,
            List<ExportFileEntry> additionalEntries) throws IOException {
        // start an entry for the manifest file
        zipOut.putNextEntry(new ZipEntry(MANIFEST_FILE_NAME));

        // begin the XML document
        XmlSerializer xml = XMLUtils.getXmlSerializer(true);
        xml.setOutput(zipOut, ENCODING);
        xml.startDocument(ENCODING, Boolean.TRUE);

        // write the root <archive> tag
        xml.startTag(null, ARCHIVE_ELEM);
        xml.attribute(null, TYPE_ATTR, FILE_TYPE_ARCHIVE);

        // write an <exported> tag
        xml.startTag(null, EXPORTED_TAG);
        xml.attribute(null, WHEN_ATTR, XMLUtils.saveDate(new Date()));
        xml.endTag(null, EXPORTED_TAG);

        // write a file entry for the sync metadata
        writeManifestFileTag(xml, METADATA_ENTRY_NAME, METADATA_FILE_TYPE, "1");

        // write entries for each other file in the archive
        for (ExportFileEntry e : additionalEntries) {
            writeManifestFileTag(xml, e.getFilename(), e.getType(),
                e.getVersion());
        }

        // end the document
        xml.endTag(null, ARCHIVE_ELEM);
        xml.endDocument();

        zipOut.closeEntry();
    }

    private void writeManifestFileTag(XmlSerializer xml, String filename,
            String type, String version) throws IOException {
        xml.startTag(null, FILE_ELEM);
        xml.attribute(null, FILE_NAME_ATTR, filename);
        if (StringUtils.hasValue(type))
            xml.attribute(null, TYPE_ATTR, type);
        if (StringUtils.hasValue(version))
            xml.attribute(null, VERSION_ATTR, version);
        xml.endTag(null, FILE_ELEM);
    }

    private static final String METADATA_FILE_TYPE = "syncMetadata";

    private static final String METADATA_ENTRY_NAME = METADATA_FILE_TYPE
            + ".xml";

}
