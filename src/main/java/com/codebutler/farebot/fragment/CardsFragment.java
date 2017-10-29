/*
 * CardsFragment.java
 *
 * Copyright 2012-2014 Eric Butler <eric@codebutler.com>
 * Copyright 2015-2016 Michael Farrell <micolous+git@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.codebutler.farebot.fragment;

import android.app.Activity;
import android.app.ListFragment;
import android.app.LoaderManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.ClipboardManager;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CursorAdapter;
import android.widget.ListView;
import android.widget.ResourceCursorAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.codebutler.farebot.activity.CardInfoActivity;
import com.codebutler.farebot.card.Card;
import com.codebutler.farebot.card.CardType;
import com.codebutler.farebot.provider.CardDBHelper;
import com.codebutler.farebot.provider.CardProvider;
import com.codebutler.farebot.provider.CardsTableColumns;
import com.codebutler.farebot.transit.TransitIdentity;
import com.codebutler.farebot.util.ExportHelper;
import com.codebutler.farebot.util.TripObfuscator;
import com.codebutler.farebot.util.Utils;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.simpleframework.xml.Serializer;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;

import au.id.micolous.farebot.R;
import au.id.micolous.metrodroid.MetrodroidApplication;

public class CardsFragment extends ListFragment {
    private static final String TAG = "CardsFragment";
    private static final int REQUEST_SELECT_FILE = 1;
    private static final int REQUEST_SAVE_FILE = 2;
    private static final String STD_EXPORT_FILENAME = "Metrdroid-Export.xml";
    private static final String SD_EXPORT_PATH = Environment.getExternalStorageDirectory() + "/" + STD_EXPORT_FILENAME;

    private Map<String, TransitIdentity> mDataCache;

    private LoaderManager.LoaderCallbacks<Cursor> mLoaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>() {
        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle bundle) {
            return new CursorLoader(getActivity(), CardProvider.CONTENT_URI_CARD,
                    CardDBHelper.PROJECTION,
                    null,
                    null,
                    CardsTableColumns.SCANNED_AT + " DESC, " + CardsTableColumns._ID + " DESC");
        }

        @Override
        public void onLoadFinished(Loader<Cursor> cursorLoader, Cursor cursor) {
            if (getListAdapter() == null) {
                setListAdapter(new CardsAdapter());
                setListShown(true);
                setEmptyText(getString(R.string.no_scanned_cards));
            }

            ((CursorAdapter) getListAdapter()).swapCursor(cursor);
        }

        @Override
        public void onLoaderReset(Loader<Cursor> cursorLoader) {
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);

        mDataCache = new HashMap<>();
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        registerForContextMenu(getListView());

        getLoaderManager().initLoader(0, null, mLoaderCallbacks);
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        Uri uri = ContentUris.withAppendedId(CardProvider.CONTENT_URI_CARD, id);
        Intent intent = new Intent(getActivity(), CardInfoActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.setData(uri);
        startActivity(intent);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.cards_menu, menu);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo menuInfo) {
        getActivity().getMenuInflater().inflate(R.menu.card_context_menu, menu);
    }

    @Override
    public boolean onContextItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == R.id.delete_card) {
            long id = ((AdapterView.AdapterContextMenuInfo) item.getMenuInfo()).id;
            Uri uri = ContentUris.withAppendedId(CardProvider.CONTENT_URI_CARD, id);
            getActivity().getContentResolver().delete(uri, null, null);
            return true;
        }
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        try {
            @SuppressWarnings("deprecation")
            ClipboardManager clipboard;
            String xml;
            Intent i;
            Uri uri;

            switch (item.getItemId()) {
                case R.id.import_clipboard:
                    clipboard = (ClipboardManager) getActivity().getSystemService(Activity.CLIPBOARD_SERVICE);
                    onCardsImported(ExportHelper.importCardsXml(getActivity(), clipboard.getText().toString()));
                    return true;

                case R.id.import_file:
                    uri = Uri.fromFile(Environment.getExternalStorageDirectory());
                    i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.putExtra(Intent.EXTRA_STREAM, uri);
                    // Some files are text/xml, some are application/xml.
                    // In Android 4.4 and later, we can say the right thing!
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        i.setType("*/*");
                        String[] mimetypes = {"application/xml", "text/xml"};
                        i.putExtra(Intent.EXTRA_MIME_TYPES, mimetypes);
                    } else {
                        // Failsafe, used in the emulator for local files
                        i.setType("text/xml");
                    }
                    startActivityForResult(Intent.createChooser(i, Utils.localizeString(R.string.select_file)), REQUEST_SELECT_FILE);
                    return true;

                case R.id.import_sd:
                    xml = FileUtils.readFileToString(new File(SD_EXPORT_PATH));
                    onCardsImported(ExportHelper.importCardsXml(getActivity(), xml));
                    return true;

                case R.id.copy_xml:
                    clipboard = (ClipboardManager) getActivity().getSystemService(Activity.CLIPBOARD_SERVICE);
                    clipboard.setText(ExportHelper.exportCardsXml(getActivity()));
                    Toast.makeText(getActivity(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
                    return true;

                case R.id.share_xml:
                    i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, ExportHelper.exportCardsXml(getActivity()));
                    startActivity(i);
                    return true;

                case R.id.save_xml:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("text/xml");
                        i.putExtra(Intent.EXTRA_TITLE, STD_EXPORT_FILENAME);
                        startActivityForResult(Intent.createChooser(i, Utils.localizeString(R.string.export_filename)), REQUEST_SAVE_FILE);
                    } else {
                        xml = ExportHelper.exportCardsXml(getActivity());
                        File file = new File(SD_EXPORT_PATH);
                        FileUtils.writeStringToFile(file, xml, "UTF-8");
                        Toast.makeText(getActivity(), R.string.saved_xml, Toast.LENGTH_SHORT).show();
                    }
                    return true;
            }
        } catch (Exception ex) {
            Utils.showError(getActivity(), ex);
        }
        return false;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        Uri uri;
        try {
            if (resultCode == Activity.RESULT_OK) {
                switch (requestCode) {
                    case REQUEST_SELECT_FILE:
                        uri = data.getData();
                        Log.d(TAG, "REQUEST_SELECT_FILE content_type = " + getActivity().getContentResolver().getType(uri));
                        InputStream stream = getActivity().getContentResolver().openInputStream(uri);
                        String xml = org.apache.commons.io.IOUtils.toString(stream, Charset.defaultCharset());
                        onCardsImported(ExportHelper.importCardsXml(getActivity(), xml));
                        break;

                    case REQUEST_SAVE_FILE:
                        uri = data.getData();
                        Log.d(TAG, "REQUEST_SAVE_FILE");
                        OutputStream os = getActivity().getContentResolver().openOutputStream(uri);
                        xml = ExportHelper.exportCardsXml(getActivity());
                        IOUtils.write(xml, os, Charset.defaultCharset());
                        Toast.makeText(getActivity(), R.string.saved_xml_custom, Toast.LENGTH_SHORT).show();
                        break;
                }
            }
        } catch (Exception ex) {
            Utils.showError(getActivity(), ex);
        }
    }

    private void onCardsImported(Uri[] uris) {
        ((CursorAdapter) ((ListView) getView().findViewById(android.R.id.list)).getAdapter()).notifyDataSetChanged();
        Toast.makeText(getActivity(), Utils.localizePlural(R.plurals.cards_imported, uris.length, uris.length), Toast.LENGTH_SHORT).show();

        if (uris.length == 1) {
            startActivity(new Intent(Intent.ACTION_VIEW, uris[0]));
        }
    }

    private class CardsAdapter extends ResourceCursorAdapter {
        public CardsAdapter() {
            super(getActivity(), android.R.layout.simple_list_item_2, null, false);
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            int type = cursor.getInt(cursor.getColumnIndex(CardsTableColumns.TYPE));
            String serial = cursor.getString(cursor.getColumnIndex(CardsTableColumns.TAG_SERIAL));
            Calendar scannedAt = GregorianCalendar.getInstance();
            scannedAt.setTimeInMillis(cursor.getLong(cursor.getColumnIndex(CardsTableColumns.SCANNED_AT)));
            String label = cursor.getString(cursor.getColumnIndex(CardsTableColumns.LABEL));

            String cacheKey = serial + scannedAt.getTimeInMillis();

            scannedAt = TripObfuscator.maybeObfuscateTS(scannedAt);

            if (!mDataCache.containsKey(cacheKey)) {
                String data = cursor.getString(cursor.getColumnIndex(CardsTableColumns.DATA));
                try {
                    Serializer serializer = MetrodroidApplication.getInstance().getSerializer();
                    mDataCache.put(cacheKey, Card.fromXml(serializer, data).parseTransitIdentity());
                } catch (Exception ex) {
                    String error = String.format("Error: %s", Utils.getErrorMessage(ex));
                    mDataCache.put(cacheKey, new TransitIdentity(error, null));
                }
            }

            TransitIdentity identity = mDataCache.get(cacheKey);

            TextView textView1 = (TextView) view.findViewById(android.R.id.text1);
            TextView textView2 = (TextView) view.findViewById(android.R.id.text2);

            if (identity != null) {
                if (label != null && !label.equals("")) {
                    // This is used for imported cards from mfcdump_to_farebotxml.py
                    // Used for development and testing. We should always show this.
                    textView1.setText(String.format("%s: %s", identity.getName(), label));
                } else if (MetrodroidApplication.hideCardNumbers()) {
                    // User doesn't want to show any card numbers.
                    textView1.setText(String.format("%s", identity.getName()));
                } else {
                    // User wants to show card numbers (default).
                    if (identity.getSerialNumber() != null) {
                        textView1.setText(String.format("%s: %s", identity.getName(), identity.getSerialNumber()));
                    } else {
                        // Fall back to showing the serial number of the NFC chip.
                        textView1.setText(String.format("%s: %s", identity.getName(), serial));
                    }
                }
                textView2.setText(getString(R.string.scanned_at_format, Utils.timeFormat(scannedAt),
                        Utils.dateFormat(scannedAt)));

            } else {
                textView1.setText(getString(R.string.unknown_card));
                if (MetrodroidApplication.hideCardNumbers()) {
                    textView2.setText(String.format("%s", CardType.values()[type].toString()));
                } else {
                    textView2.setText(String.format("%s - %s", CardType.values()[type].toString(), serial));
                }
            }
        }

        @Override
        protected void onContentChanged() {
            super.onContentChanged();
            mDataCache.clear();
        }
    }
}
