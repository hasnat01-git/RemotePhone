package com.hasnat.remotephone;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.view.View;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.SimpleCursorAdapter;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class ContactsActivity extends AppCompatActivity implements SearchView.OnQueryTextListener {

    public static final String ACTION_DIAL_CONTACT = "com.hasnat.remotephone.DIAL_CONTACT";
    public static final String EXTRA_PHONE_NUMBER = "phone_number";

    private SimpleCursorAdapterWithFilter adapter;
    private ListView contactsListView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contacts);

        SearchView searchView = findViewById(R.id.contact_search_view);
        searchView.setOnQueryTextListener(this);

        String[] projection = new String[]{
                ContactsContract.CommonDataKinds.Phone._ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
        };

        // Query the contacts and get a Cursor
        Cursor cursor = getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");

        String[] fromColumns = {
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
        };

        int[] toViews = {
                android.R.id.text1,
                android.R.id.text2
        };

        // Use the custom adapter to handle filtering
        adapter = new SimpleCursorAdapterWithFilter(
                this,
                android.R.layout.simple_list_item_2,
                cursor,
                fromColumns,
                toViews,
                0);

        contactsListView = findViewById(R.id.contacts_list_view);
        contactsListView.setAdapter(adapter);

        contactsListView.setOnItemClickListener((parent, view, position, id) -> {
            Cursor itemCursor = (Cursor) adapter.getItem(position);
            @SuppressLint("Range") String contactName = itemCursor.getString(itemCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
            @SuppressLint("Range") String phoneNumber = itemCursor.getString(itemCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));

            new AlertDialog.Builder(this)
                    .setTitle("Confirm Call")
                    .setMessage("Do you want to call " + contactName + " at " + phoneNumber + "?")
                    .setPositiveButton("Yes", (dialog, which) -> {
                        Intent broadcastIntent = new Intent(ACTION_DIAL_CONTACT);
                        broadcastIntent.putExtra(EXTRA_PHONE_NUMBER, phoneNumber);
                        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent);
                        finish();
                    })
                    .setNegativeButton("No", null)
                    .show();
        });
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        return false;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        // Filter the custom adapter based on the search query
        adapter.getFilter().filter(newText);
        return false;
    }

    class SimpleCursorAdapterWithFilter extends SimpleCursorAdapter {
        private String filterQuery = null;

        public SimpleCursorAdapterWithFilter(Context context, int layout, Cursor c, String[] from, int[] to, int flags) {
            super(context, layout, c, from, to, flags);
        }

        @Override
        public Cursor runQueryOnBackgroundThread(CharSequence constraint) {
            if (getFilterQueryProvider() != null) {
                return getFilterQueryProvider().runQuery(constraint);
            }

            // This is the core logic for filtering
            filterQuery = constraint != null ? constraint.toString() : null;
            String selection = null;
            String[] selectionArgs = null;

            if (filterQuery != null && !filterQuery.isEmpty()) {
                String likePattern = "%" + filterQuery + "%";
                selection = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ? OR " +
                        ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ?";
                selectionArgs = new String[]{likePattern, likePattern};
            }

            String[] projection = new String[]{
                    ContactsContract.CommonDataKinds.Phone._ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
            };

            return getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
        }
    }
}