/*
 *  Copyright 2017 the MapStruct authors (http://www.mapstruct.org/)
 *  and/or other contributors as indicated by the @authors tag. See the
 *  copyright.txt file in the distribution for a full listing of all
 *  contributors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.mapstruct.intellij.util;

import java.util.Optional;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoUtil;
import com.intellij.openapi.module.LanguageLevelUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNameValuePair;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;

import static com.intellij.codeInsight.intention.AddAnnotationPsiFix.addPhysicalAnnotation;
import static com.intellij.codeInsight.intention.AddAnnotationPsiFix.removePhysicalAnnotations;

/**
 * Utils for working with mapstruct annotation.
 *
 * @author Filip Hrisafov
 */
public class MapstructAnnotationUtils {

    private MapstructAnnotationUtils() {
    }

    /**
     * This method adds the {@code mappingAnnotation} to the given {@code mappingMethod}. It takes into
     * consideration, the current mappings, language level and whether the {@link org.mapstruct.Mapping} repeatable
     * annotation can be used.
     *
     * @param project the project
     * @param mappingMethod the method to which the annotation needs to be added
     * @param mappingAnnotation the {@link org.mapstruct.Mapping} annotation
     */
    public static void addMappingAnnotation(@NotNull Project project,
        @NotNull PsiMethod mappingMethod,
        @NotNull PsiAnnotation mappingAnnotation) {
        Pair<PsiAnnotation, Optional<PsiAnnotation>> mappingsPair = findOrCreateMappingsAnnotation(
            project,
            mappingMethod
        );
        final PsiFile containingFile = mappingMethod.getContainingFile();

        PsiAnnotation containerAnnotation = mappingsPair.getFirst();
        PsiAnnotation newAnnotation = createNewAnnotation(
            project,
            mappingMethod,
            containerAnnotation,
            mappingAnnotation
        );
        if ( newAnnotation != null ) {
            if ( containerAnnotation != null && containerAnnotation.isPhysical() ) {
                runWriteCommandAction(
                    project,
                    () -> containerAnnotation.replace( newAnnotation ),
                    containingFile
                );
            }
            else {
                String fqn = containerAnnotation != null ? MapstructUtil.MAPPINGS_ANNOTATION_FQN :
                    MapstructUtil.MAPPING_ANNOTATION_FQN;
                PsiNameValuePair[] attributes = newAnnotation.getParameterList().getAttributes();
                Optional<String> annotationToRemove = mappingsPair.getSecond()
                    .map( PsiAnnotation::getQualifiedName );

                runWriteCommandAction(
                    project, () -> {
                        // If there was a mapping annotation previously we need to remove it (it is already included
                        // in the new attributes
                        annotationToRemove
                            .ifPresent( qualifiedName -> removePhysicalAnnotations(
                                mappingMethod,
                                qualifiedName
                            ) );

                        PsiAnnotation inserted = addPhysicalAnnotation(
                            fqn,
                            attributes,
                            mappingMethod.getModifierList()
                        );
                        JavaCodeStyleManager.getInstance( project ).shortenClassReferences( inserted );
                    }, containingFile );

                UndoUtil.markPsiFileForUndo( containingFile );
            }
        }
    }

    /**
     * This methods looks for the {@link org.mapstruct.Mappings} annotation on the given {@code mappingMethod},
     * if the annotation was not found and the {@link org.mapstruct.Mapping} repeatable annotation cannot be used
     * (Language level is lower than JDK 1.8 and/or mapstruct jdk8 is not present) it creates a dummy
     * {@link org.mapstruct.Mappings} annotation.
     * <p>
     * In case the method was already annotated with {@link org.mapstruct.Mapping}, it returns a dummy
     * {@link org.mapstruct.Mappings} annotation that contains the already attached {@link org.mapstruct.Mapping} and
     * returns the annotation that needs to be removed from the method as a second value of the {@link Pair}
     *
     * @param project the project
     * @param mappingMethod the method that needs to be checked
     *
     * @return see the description
     */
    private static Pair<PsiAnnotation, Optional<PsiAnnotation>> findOrCreateMappingsAnnotation(
        @NotNull Project project,
        @NotNull PsiMethod mappingMethod) {
        PsiAnnotation mappingsAnnotation = AnnotationUtil.findAnnotation(
            mappingMethod,
            MapstructUtil.MAPPINGS_ANNOTATION_FQN
        );
        if ( mappingsAnnotation != null ) {
            return Pair.create( mappingsAnnotation, Optional.empty() );
        }

        final PsiFile containingFile = mappingMethod.getContainingFile();
        if ( !canUseRepeatableMapping( mappingMethod ) ) {
            PsiAnnotation oldMappingAnnotation = AnnotationUtil.findAnnotation(
                mappingMethod,
                MapstructUtil.MAPPING_ANNOTATION_FQN
            );
            String otherMappings = oldMappingAnnotation == null ? "" : "\n" + oldMappingAnnotation.getText();

            mappingsAnnotation = JavaPsiFacade.getElementFactory( project )
                .createAnnotationFromText(
                    "@" + MapstructUtil.MAPPINGS_ANNOTATION_FQN + "({" + otherMappings + "\n})",
                    containingFile
                );
            return Pair.create( mappingsAnnotation, Optional.ofNullable( oldMappingAnnotation ) );
        }
        return Pair.create( null, Optional.empty() );
    }

    /**
     * Run default {@link WriteCommandAction}.
     *
     * @param project the project in which to run the action
     * @param runnable the runnable to be executed
     * @param containingFile the file in which to run
     */
    private static void runWriteCommandAction(Project project, Runnable runnable, PsiFile containingFile) {
        WriteCommandAction.runWriteCommandAction( project, null, null, runnable, containingFile );
    }

    /**
     * Create a new annotation that can be added to a method. This method takes into consideration the different
     * possibilities of having a array based repeatable annotation declaration.
     *
     * @param project the project
     * @param container the container for the annotation
     * @param containerAnnotation the container annotation for {@code mappingAnnotation}
     * @param mappingAnnotation the single mapping annotation that needs to be taken into consideration when creating
     *
     * @return the annotation that should be added to the mapping method
     */
    private static PsiAnnotation createNewAnnotation(@NotNull Project project,
        PsiElement container,
        PsiAnnotation containerAnnotation,
        @NotNull PsiAnnotation mappingAnnotation) {
        if ( containerAnnotation == null ) {
            return mappingAnnotation;
        }
        if ( !containerAnnotation.getText().contains( "{" ) ) {
            //The container annotation contains a single value not declared as array
            final PsiNameValuePair[] attributes = containerAnnotation.getParameterList().getAttributes();
            if ( attributes.length == 1 ) {
                final String currentMappings = attributes[0].getText();
                return JavaPsiFacade.getInstance( project ).getElementFactory().createAnnotationFromText(
                    "@" + MapstructUtil.MAPPINGS_ANNOTATION_FQN + "({\n" + currentMappings + ",\n " +
                        mappingAnnotation.getText() + "\n})", container );

            }
        }
        else {
            final int curlyBraceIndex = containerAnnotation.getText().lastIndexOf( '}' );
            if ( curlyBraceIndex > 0 ) {
                final String textBeforeCurlyBrace = containerAnnotation.getText().substring( 0, curlyBraceIndex );
                int braceIndex = textBeforeCurlyBrace.lastIndexOf( ')' );
                final String textToPreserve =
                    braceIndex < 0 ? textBeforeCurlyBrace : textBeforeCurlyBrace.substring( 0, braceIndex ) + "),\n";
                return JavaPsiFacade.getInstance( project ).getElementFactory().createAnnotationFromText(
                    textToPreserve + " " + mappingAnnotation.getText() + "\n})", container );
            }
            else {
                throw new IncorrectOperationException( containerAnnotation.getText() );
            }
        }
        return null;
    }

    /**
     * Checks if the {@link java.lang.annotation.Repeatable} {@link org.mapstruct.Mapping} annotation can be used.
     * The annotation can be used when the following is satisfied:
     * <ul>
     * <li>The {@link LanguageLevel} of the module is at least {@link LanguageLevel#JDK_1_8}</li>
     * <li>Mapstruct jdk 8 is present in the module</li>
     * </ul>
     *
     * @param psiElement element from the module
     *
     * @return {@code true} if the {@link java.lang.annotation.Repeatable} {@link org.mapstruct.Mapping} annotation
     * can be used, {@code false} otherwise
     */
    private static boolean canUseRepeatableMapping(PsiElement psiElement) {
        Module module = ModuleUtilCore.findModuleForPsiElement( psiElement );
        return module != null
            && LanguageLevelUtil.getEffectiveLanguageLevel( module ).isAtLeast( LanguageLevel.JDK_1_8 )
            && MapstructUtil.isMapStructJdk8Present( module );
    }
}
