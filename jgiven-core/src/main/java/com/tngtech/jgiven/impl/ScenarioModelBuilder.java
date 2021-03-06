package com.tngtech.jgiven.impl;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.CaseFormat;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.tngtech.jgiven.annotation.*;
import com.tngtech.jgiven.attachment.Attachment;
import com.tngtech.jgiven.config.AbstractJGivenConfiguration;
import com.tngtech.jgiven.config.ConfigurationUtil;
import com.tngtech.jgiven.config.DefaultConfiguration;
import com.tngtech.jgiven.config.TagConfiguration;
import com.tngtech.jgiven.exception.JGivenWrongUsageException;
import com.tngtech.jgiven.format.ObjectFormatter;
import com.tngtech.jgiven.impl.format.ParameterFormattingUtil;
import com.tngtech.jgiven.impl.intercept.ScenarioListener;
import com.tngtech.jgiven.impl.util.AnnotationUtil;
import com.tngtech.jgiven.impl.util.AssertionUtil;
import com.tngtech.jgiven.impl.util.ReflectionUtil;
import com.tngtech.jgiven.impl.util.WordUtil;
import com.tngtech.jgiven.report.model.*;

public class ScenarioModelBuilder implements ScenarioListener {
    private static final Logger log = LoggerFactory.getLogger( ScenarioModelBuilder.class );

    private static final Set<String> STACK_TRACE_FILTER = ImmutableSet
        .of( "sun.reflect", "com.tngtech.jgiven.impl.intercept", "com.tngtech.jgiven.impl.intercept", "$$EnhancerByCGLIB$$",
            "java.lang.reflect", "net.sf.cglib.proxy", "com.sun.proxy" );
    private static final boolean FILTER_STACK_TRACE = Config.config().filterStackTrace();

    private ScenarioModel scenarioModel;
    private ScenarioCaseModel scenarioCaseModel;
    private StepModel currentStep;
    private final Stack<StepModel> parentSteps = new Stack<StepModel>();

    /**
     * In case the current step is a step with nested steps, this list contains these steps
     */
    private List<StepModel> nestedSteps;

    private Word introWord;

    private long scenarioStartedNanos;

    private AbstractJGivenConfiguration configuration = new DefaultConfiguration();

    private ReportModel reportModel;

    public void setReportModel( ReportModel reportModel ) {
        this.reportModel = reportModel;
    }

    @Override
    public void scenarioStarted( String description ) {
        scenarioStartedNanos = System.nanoTime();
        String readableDescription = description;

        if( description.contains( "_" ) ) {
            readableDescription = description.replace( '_', ' ' );
        } else if( !description.contains( " " ) ) {
            readableDescription = camelCaseToCapitalizedReadableText( description );
        }

        scenarioCaseModel = new ScenarioCaseModel();

        scenarioModel = new ScenarioModel();
        scenarioModel.addCase( scenarioCaseModel );
        scenarioModel.setDescription( readableDescription );
    }

    private static String camelCaseToCapitalizedReadableText( String camelCase ) {
        return WordUtil.capitalize( camelCaseToReadableText( camelCase ) );
    }

    private static String camelCaseToReadableText( String camelCase ) {
        return CaseFormat.LOWER_CAMEL.to( CaseFormat.LOWER_UNDERSCORE, camelCase ).replace( '_', ' ' );
    }

    public void addStepMethod( Method paramMethod, List<NamedArgument> arguments, InvocationMode mode, boolean hasNestedSteps ) {
        StepModel stepModel = createStepModel( paramMethod, arguments, mode );

        if( parentSteps.empty() ) {
            getCurrentScenarioCase().addStep( stepModel );
        } else {
            parentSteps.peek().addNestedStep( stepModel );
        }

        if( hasNestedSteps ) {
            parentSteps.push( stepModel );
        }
        currentStep = stepModel;
    }

    StepModel createStepModel( Method paramMethod, List<NamedArgument> arguments, InvocationMode mode ) {
        StepModel stepModel = new StepModel();

        stepModel.setName( getDescription( paramMethod ) );

        ExtendedDescription extendedDescriptionAnnotation = paramMethod.getAnnotation( ExtendedDescription.class );
        if( extendedDescriptionAnnotation != null ) {
            stepModel.setExtendedDescription( extendedDescriptionAnnotation.value() );
        }

        List<NamedArgument> nonHiddenArguments = filterHiddenArguments( arguments, paramMethod.getParameterAnnotations() );

        ParameterFormattingUtil parameterFormattingUtil = new ParameterFormattingUtil( configuration );
        List<ObjectFormatter<?>> formatters = parameterFormattingUtil.getFormatter( paramMethod.getParameterTypes(), getNames( arguments ),
            paramMethod.getParameterAnnotations() );
        stepModel.setWords( new StepFormatter( stepModel.getName(), nonHiddenArguments, formatters ).buildFormattedWords() );

        if( introWord != null ) {
            stepModel.addIntroWord( introWord );
            introWord = null;
        }

        stepModel.setStatus( mode.toStepStatus() );
        return stepModel;
    }

    private List<NamedArgument> filterHiddenArguments( List<NamedArgument> arguments, Annotation[][] parameterAnnotations ) {
        List<NamedArgument> result = Lists.newArrayList();
        for( int i = 0; i < parameterAnnotations.length; i++ ) {
            if( !AnnotationUtil.isHidden( parameterAnnotations[i] ) ) {
                result.add( arguments.get( i ) );
            }
        }
        return result;
    }

    @Override
    public void introWordAdded( String value ) {
        introWord = new Word();
        introWord.setIntroWord( true );
        introWord.setValue( value );
    }

    private ScenarioCaseModel getCurrentScenarioCase() {
        if( scenarioCaseModel == null ) {
            scenarioStarted( "A Scenario" );
        }
        return scenarioCaseModel;
    }

    @Override
    public void stepMethodInvoked( Method method, List<NamedArgument> arguments, InvocationMode mode, boolean hasNestedSteps ) {
        if( method.isAnnotationPresent( IntroWord.class ) ) {
            introWordAdded( getDescription( method ) );
        } else {
            addStepMethod( method, arguments, mode, hasNestedSteps );
        }
    }

    public void setMethodName( String methodName ) {
        scenarioModel.setTestMethodName( methodName );
    }

    public void setArguments( List<String> arguments ) {
        scenarioCaseModel.setExplicitArguments( arguments );
    }

    public void setParameterNames( List<String> parameterNames ) {
        scenarioModel.setExplicitParameters( removeUnderlines( parameterNames ) );
    }

    private static List<String> removeUnderlines( List<String> parameterNames ) {
        List<String> result = Lists.newArrayListWithCapacity( parameterNames.size() );
        for( String paramName : parameterNames ) {
            result.add( WordUtil.fromSnakeCase( paramName ) );
        }
        return result;
    }

    private String getDescription( Method paramMethod ) {
        if( paramMethod.isAnnotationPresent( Hidden.class ) ) {
            return "";
        }

        Description description = paramMethod.getAnnotation( Description.class );
        if( description != null ) {
            return description.value();
        }
        As as = paramMethod.getAnnotation( As.class );
        if( as != null ) {
            return as.value();
        }

        return nameWithSpaces( paramMethod );
    }

    public void setSuccess( boolean success ) {
        scenarioCaseModel.setSuccess( success );
    }

    public void setException( Throwable throwable ) {
        scenarioCaseModel.setErrorMessage( throwable.getClass().getName() + ": " + throwable.getMessage() );
        scenarioCaseModel.setStackTrace( getStackTrace( throwable, FILTER_STACK_TRACE ) );
    }

    private List<String> getStackTrace( Throwable exception, boolean filterStackTrace ) {
        StackTraceElement[] stackTraceElements = exception.getStackTrace();
        ArrayList<String> stackTrace = new ArrayList<String>( stackTraceElements.length );

        outer:
        for( StackTraceElement element : stackTraceElements ) {
            if( filterStackTrace ) {
                for( String filter : STACK_TRACE_FILTER ) {
                    if( element.getClassName().contains( filter ) ) {
                        continue outer;
                    }
                }
            }
            stackTrace.add( element.toString() );
        }
        return stackTrace;
    }

    private static String nameWithSpaces( Method paramMethod ) {
        String paraMethodName = paramMethod.getName();
        if( paramMethod.getName().contains( "_" ) ) {
            return WordUtil.fromSnakeCase( paraMethodName );
        }
        return camelCaseToReadableText( paraMethodName );
    }

    @Override
    public void stepMethodFailed( Throwable t ) {
        if( currentStep != null ) {
            currentStep.setStatus( StepStatus.FAILED );
        }
    }

    @Override
    public void stepMethodFinished( long durationInNanos, boolean hasNestedSteps ) {
        if( currentStep != null ) {
            currentStep.setDurationInNanos( durationInNanos );
        }
        if( hasNestedSteps ) {
            if( currentStep.getStatus() != StepStatus.FAILED ) {
                currentStep.setStatus( getStatusFromNestedSteps( currentStep.getNestedSteps() ) );
            }
            parentSteps.pop();
        }

        if( !parentSteps.empty() ) {
            currentStep = parentSteps.peek();
        }
    }

    private StepStatus getStatusFromNestedSteps( List<StepModel> nestedSteps ) {
        StepStatus status = StepStatus.PASSED;
        for( StepModel nestedModel : nestedSteps ) {
            StepStatus nestedStatus = nestedModel.getStatus();

            switch( nestedStatus ) {
                case FAILED:
                    return StepStatus.FAILED;
                case PENDING:
                    status = StepStatus.PENDING;
                    break;
            }
        }
        return status;
    }

    @Override
    public void scenarioFailed( Throwable e ) {
        setSuccess( false );
        setException( e );
    }

    @Override
    public void scenarioStarted( Class<?> testClass, Method method, List<NamedArgument> namedArguments ) {
        readConfiguration( testClass );
        readAnnotations( testClass, method );
        setParameterNames( getNames( namedArguments ) );

        // must come at last
        setMethodName( method.getName() );

        ParameterFormattingUtil parameterFormattingUtil = new ParameterFormattingUtil( configuration );
        List<ObjectFormatter<?>> formatter = parameterFormattingUtil.getFormatter( method.getParameterTypes(), getNames( namedArguments ),
            method.getParameterAnnotations() );

        setArguments( parameterFormattingUtil.toStringList( formatter, getValues( namedArguments ) ) );
        setCaseDescription( method, namedArguments );
    }

    private void setCaseDescription( Method method, List<NamedArgument> namedArguments ) {
        if( method.isAnnotationPresent( CaseDescription.class ) ) {
            CaseDescription annotation = method.getAnnotation( CaseDescription.class );
            CaseDescriptionProvider caseDescriptionProvider = ReflectionUtil.newInstance( annotation.provider() );
            String value = annotation.value();
            List<?> values;
            if( annotation.formatValues() ) {
                values = scenarioCaseModel.getExplicitArguments();
            } else {
                values = getValues( namedArguments );
            }
            String caseDescription = caseDescriptionProvider.description( value, scenarioModel.getExplicitParameters(), values );
            scenarioCaseModel.setDescription( caseDescription );
        }
    }

    private List<Object> getValues( List<NamedArgument> namedArguments ) {
        List<Object> result = Lists.newArrayList();
        for( NamedArgument a : namedArguments ) {
            result.add( a.value );
        }
        return result;
    }

    private List<String> getNames( List<NamedArgument> namedArguments ) {
        List<String> result = Lists.newArrayList();
        for( NamedArgument a : namedArguments ) {
            result.add( a.name );
        }
        return result;
    }

    private void readConfiguration( Class<?> testClass ) {
        configuration = ConfigurationUtil.getConfiguration( testClass );
    }

    private void readAnnotations( Class<?> testClass, Method method ) {
        String scenarioDescription = method.getName();

        if( method.isAnnotationPresent( Description.class ) ) {
            scenarioDescription = method.getAnnotation( Description.class ).value();
        } else if( method.isAnnotationPresent( As.class ) ) {
            scenarioDescription = method.getAnnotation( As.class ).value();
        }

        scenarioStarted( scenarioDescription );

        if( method.isAnnotationPresent( ExtendedDescription.class ) ) {
            scenarioModel.setExtendedDescription( method.getAnnotation( ExtendedDescription.class ).value() );
        }

        if( method.isAnnotationPresent( NotImplementedYet.class ) || method.isAnnotationPresent( Pending.class ) ) {
            scenarioModel.setPending();
        }

        if( scenarioCaseModel.getCaseNr() == 1 ) {
            addTags( testClass.getAnnotations() );
            addTags( method.getAnnotations() );
        }
    }

    public void addTags( Annotation... annotations ) {
        for( Annotation annotation : annotations ) {
            List<Tag> tags = toTags( annotation );
            this.reportModel.addTags( tags );
            this.scenarioModel.addTags( tags );
        }
    }

    public List<Tag> toTags( Annotation annotation ) {
        Class<? extends Annotation> annotationType = annotation.annotationType();
        IsTag isTag = annotationType.getAnnotation( IsTag.class );
        TagConfiguration tagConfig;
        if( isTag != null ) {
            tagConfig = fromIsTag( isTag, annotation );
        } else {
            tagConfig = configuration.getTagConfiguration( annotationType );
        }

        if( tagConfig == null ) {
            return Collections.emptyList();
        }

        Tag tag = new Tag( tagConfig.getAnnotationType() );

        if( !Strings.isNullOrEmpty( tagConfig.getName() ) ) {
            tag.setName( tagConfig.getName() );
        }

        if( tagConfig.isPrependType() ) {
            tag.setPrependType( true );
        }

        if( !Strings.isNullOrEmpty( tagConfig.getCssClass() ) ) {
            tag.setCssClass( tagConfig.getCssClass() );
        }

        if( !Strings.isNullOrEmpty( tagConfig.getColor() ) ) {
            tag.setColor( tagConfig.getColor() );
        }

        if( !Strings.isNullOrEmpty( tagConfig.getStyle() ) ) {
            tag.setStyle( tagConfig.getStyle() );
        }

        Object value = tagConfig.getDefaultValue();
        if( !Strings.isNullOrEmpty( tagConfig.getDefaultValue() ) ) {
            tag.setValue( tagConfig.getDefaultValue() );
        }

        if( tagConfig.isIgnoreValue() ) {
            tag.setDescription( getDescriptionFromGenerator( tagConfig, annotation, value ) );
            return Arrays.asList( tag );
        }

        tag.setTags( tagConfig.getTags() );

        try {
            Method method = annotationType.getMethod( "value" );
            value = method.invoke( annotation );
            if( value != null ) {
                if( value.getClass().isArray() ) {
                    Object[] objectArray = (Object[]) value;
                    if( tagConfig.isExplodeArray() ) {
                        List<Tag> explodedTags = getExplodedTags( tag, objectArray, annotation, tagConfig );
                        return explodedTags;
                    }
                    tag.setValue( toStringList( objectArray ) );

                } else {
                    tag.setValue( String.valueOf( value ) );
                }
            }
        } catch( NoSuchMethodException ignore ) {

        } catch( Exception e ) {
            log.error( "Error while getting 'value' method of annotation " + annotation, e );
        }

        tag.setDescription( getDescriptionFromGenerator( tagConfig, annotation, value ) );
        tag.setHref( getHref( tagConfig, annotation, value ) );
        return Arrays.asList( tag );
    }

    public TagConfiguration fromIsTag( IsTag isTag, Annotation annotation ) {

        String name = Strings.isNullOrEmpty( isTag.name() ) ? isTag.type() : isTag.name();

        return TagConfiguration.builder( annotation.annotationType() ).defaultValue( isTag.value() ).description( isTag.description() )
            .explodeArray( isTag.explodeArray() ).ignoreValue( isTag.ignoreValue() ).prependType( isTag.prependType() ).name( name )
            .descriptionGenerator( isTag.descriptionGenerator() ).cssClass( isTag.cssClass() ).color( isTag.color() )
            .style( isTag.style() ).tags( getTagNames( isTag, annotation ) )
            .href( isTag.href() ).hrefGenerator( isTag.hrefGenerator() ).build();

    }

    private List<String> getTagNames( IsTag isTag, Annotation annotation ) {
        List<Tag> tags = getTags( isTag, annotation );
        reportModel.addTags( tags );
        List<String> tagNames = Lists.newArrayList();
        for( Tag tag : tags ) {
            tagNames.add( tag.toIdString() );
        }
        return tagNames;
    }

    private List<Tag> getTags( IsTag isTag, Annotation annotation ) {
        List<Tag> allTags = Lists.newArrayList();

        for( Annotation a : annotation.annotationType().getAnnotations() ) {
            if( a.annotationType().isAnnotationPresent( IsTag.class ) ) {
                List<Tag> tags = toTags( a );
                for( Tag tag : tags ) {
                    allTags.add( tag );
                }
            }
        }

        return allTags;
    }

    private List<String> toStringList( Object[] value ) {
        Object[] array = value;
        List<String> values = Lists.newArrayList();
        for( Object v : array ) {
            values.add( String.valueOf( v ) );
        }
        return values;
    }

    private String getDescriptionFromGenerator( TagConfiguration tagConfiguration, Annotation annotation, Object value ) {
        try {
            return tagConfiguration.getDescriptionGenerator().newInstance().generateDescription( tagConfiguration, annotation, value );
        } catch( Exception e ) {
            throw new JGivenWrongUsageException(
                "Error while trying to generate the description for annotation " + annotation + " using DescriptionGenerator class "
                        + tagConfiguration.getDescriptionGenerator() + ": " + e.getMessage(), e );
        }
    }

    private String getHref( TagConfiguration tagConfiguration, Annotation annotation, Object value ) {
        try {
            return tagConfiguration.getHrefGenerator().newInstance().generateHref( tagConfiguration, annotation, value );
        } catch( Exception e ) {
            throw new JGivenWrongUsageException(
                "Error while trying to generate the href for annotation " + annotation + " using HrefGenerator class "
                        + tagConfiguration.getHrefGenerator() + ": " + e.getMessage(), e );
        }
    }

    private List<Tag> getExplodedTags( Tag originalTag, Object[] values, Annotation annotation, TagConfiguration tagConfig ) {
        List<Tag> result = Lists.newArrayList();
        for( Object singleValue : values ) {
            Tag newTag = originalTag.copy();
            newTag.setValue( String.valueOf( singleValue ) );
            newTag.setDescription( getDescriptionFromGenerator( tagConfig, annotation, singleValue ) );
            newTag.setHref( getHref( tagConfig, annotation, singleValue ) );
            result.add( newTag );
        }
        return result;
    }

    @Override
    public void scenarioFinished() {
        AssertionUtil.assertTrue( scenarioStartedNanos > 0, "Scenario has no start time" );
        long durationInNanos = System.nanoTime() - scenarioStartedNanos;
        scenarioCaseModel.setDurationInNanos( durationInNanos );
        scenarioModel.addDurationInNanos( durationInNanos );
        reportModel.addScenarioModelOrMergeWithExistingOne( scenarioModel );
    }

    @Override
    public void attachmentAdded( Attachment attachment ) {
        currentStep.setAttachment( attachment );
    }

    @Override
    public void extendedDescriptionUpdated( String extendedDescription ) {
        currentStep.setExtendedDescription( extendedDescription );
    }

    @Override
    public void sectionAdded( String sectionTitle ) {
        StepModel stepModel = new StepModel();
        stepModel.setName( sectionTitle );
        stepModel.addWords( new Word( sectionTitle ) );
        stepModel.setIsSectionTitle( true );
        getCurrentScenarioCase().addStep( stepModel );
    }

    public ReportModel getReportModel() {
        return reportModel;
    }

    public ScenarioModel getScenarioModel() {
        return scenarioModel;
    }

    public ScenarioCaseModel getScenarioCaseModel() {
        return scenarioCaseModel;
    }

}
