package com.soulbot.app.app
import com.soulbot.app.*
import com.soulbot.app.accessibility.*
import com.soulbot.app.app.*
import com.soulbot.app.automation.*
import com.soulbot.app.conversation.*
import com.soulbot.app.data.*
import com.soulbot.app.diagnostics.*
import com.soulbot.app.model.*
import com.soulbot.app.policy.*
import com.soulbot.app.workflow.greeting.*
import com.soulbot.app.workflow.match.*
import com.soulbot.app.workflow.privatechat.*
import com.soulbot.app.workflow.qiyu.*
import com.soulbot.app.workflow.square.*

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import com.google.android.material.button.MaterialButton

class ProfileSettingsActivity : BaseSettingsActivity() {
    private val visibilityOptions = ProfileVisibility.values().toList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_settings)
        setupToolbar("我的资料")

        val gender = findViewById<EditText>(R.id.etSelfGender)
        val age = findViewById<EditText>(R.id.etSelfAge)
        val region = findViewById<EditText>(R.id.etSelfRegion)
        val zodiac = findViewById<EditText>(R.id.etSelfZodiac)
        val height = findViewById<EditText>(R.id.etSelfHeight)
        val occupation = findViewById<EditText>(R.id.etSelfOccupation)
        val details = findViewById<EditText>(R.id.etSelfDetails)

        val genderVisibility = findViewById<Spinner>(R.id.spSelfGenderVisibility)
        val ageVisibility = findViewById<Spinner>(R.id.spSelfAgeVisibility)
        val regionVisibility = findViewById<Spinner>(R.id.spSelfRegionVisibility)
        val zodiacVisibility = findViewById<Spinner>(R.id.spSelfZodiacVisibility)
        val heightVisibility = findViewById<Spinner>(R.id.spSelfHeightVisibility)
        val occupationVisibility = findViewById<Spinner>(R.id.spSelfOccupationVisibility)
        val detailsVisibility = findViewById<Spinner>(R.id.spSelfDetailsVisibility)

        val profile = Prefs.getSelfProfile(this)
        gender.setText(profile.gender)
        age.setText(profile.age)
        region.setText(profile.region)
        zodiac.setText(profile.zodiac)
        height.setText(profile.height)
        occupation.setText(profile.occupation)
        details.setText(profile.details)

        bindVisibility(genderVisibility, profile.genderVisibility)
        bindVisibility(ageVisibility, profile.ageVisibility)
        bindVisibility(regionVisibility, profile.regionVisibility)
        bindVisibility(zodiacVisibility, profile.zodiacVisibility)
        bindVisibility(heightVisibility, profile.heightVisibility)
        bindVisibility(occupationVisibility, profile.occupationVisibility)
        bindVisibility(detailsVisibility, profile.detailsVisibility)

        findViewById<MaterialButton>(R.id.btnSaveSelfProfile).setOnClickListener {
            Prefs.setSelfProfile(
                this,
                SelfProfile(
                    gender = gender.text.toString(),
                    age = age.text.toString(),
                    region = region.text.toString(),
                    zodiac = zodiac.text.toString(),
                    occupation = occupation.text.toString(),
                    details = details.text.toString(),
                    height = height.text.toString(),
                    genderVisibility = selectedVisibility(genderVisibility),
                    ageVisibility = selectedVisibility(ageVisibility),
                    regionVisibility = selectedVisibility(regionVisibility),
                    zodiacVisibility = selectedVisibility(zodiacVisibility),
                    occupationVisibility = selectedVisibility(occupationVisibility),
                    detailsVisibility = selectedVisibility(detailsVisibility),
                    heightVisibility = selectedVisibility(heightVisibility),
                ),
            )
            showMessage("资料已保存，后续回复立即生效")
        }
    }

    private fun bindVisibility(spinner: Spinner, selected: ProfileVisibility) {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            visibilityOptions.map { it.label },
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.adapter = adapter
        spinner.setSelection(visibilityOptions.indexOf(selected).coerceAtLeast(0))
    }

    private fun selectedVisibility(spinner: Spinner): ProfileVisibility =
        visibilityOptions.getOrNull(spinner.selectedItemPosition) ?: ProfileVisibility.ASKED
}
