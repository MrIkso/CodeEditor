package com.mrikso.texteditor;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;

//import com.mrikso.codeeditor.TextEditor;


public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        TextEditor textEditor = findViewById(R.id.codeEditor);
        textEditor.setText("lol");
    }

}
